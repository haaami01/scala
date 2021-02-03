/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc.interpreter
package jline

import java.util.{Collections, List => JList}
import org.jline.reader.{Candidate, Completer, CompletingParsedLine, EOFError, EndOfFileException, History, LineReader, ParsedLine, Parser, Reference, SyntaxError, UserInterruptException}
import org.jline.reader.impl.{CompletionMatcherImpl, DefaultParser, LineReaderImpl}
import org.jline.terminal.Terminal

import shell.{Accumulator, ShellConfig}
import Parser.ParseContext
import org.jline.console.{CmdDesc, CmdLine}
import org.jline.keymap.KeyMap
import org.jline.utils.AttributedString
import org.jline.widget.TailTipWidgets.TipType
import org.jline.widget.{TailTipWidgets}

import java.{lang, util}
import scala.reflect.internal.Chars

/** A Reader that delegates to JLine3.
 */
class Reader private (
    config: ShellConfig,
    reader: LineReader,
    val accumulator: Accumulator,
    val completion: shell.Completion,
    terminal: Terminal) extends shell.InteractiveReader {
  override val history: shell.History = new HistoryAdaptor(reader.getHistory)
  override def interactive: Boolean = true
  protected def readOneLine(prompt: String): String = {
    try {
      reader.readLine(prompt)
    } catch {
      case _: EndOfFileException | _: UserInterruptException => reader.getBuffer.delete() ; null
    }
  }
  def redrawLine(): Unit = ???
  def reset(): Unit = accumulator.reset()
  override def close(): Unit = terminal.close()

  override def withSecondaryPrompt[T](prompt: String)(body: => T): T = {
    val oldPrompt = reader.getVariable(LineReader.SECONDARY_PROMPT_PATTERN)
    reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, prompt)
    try body
    finally reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, oldPrompt)
  }
}

object Reader {
  import org.jline.reader.LineReaderBuilder
  import org.jline.reader.impl.history.DefaultHistory
  import org.jline.terminal.TerminalBuilder

  /** Construct a Reader with various JLine3-specific set-up.
   *  The `shell.Completion` is wrapped in the `jline.Completion` bridge to enable completion from JLine3.
   */
  def apply(
      config: ShellConfig,
      repl: Repl,
      completion: shell.Completion,
      accumulator: Accumulator): Reader = {
    require(repl != null)
    if (config.isReplDebug) initLogging(trace = config.isReplTrace)

    System.setProperty(LineReader.PROP_SUPPORT_PARSEDLINE, java.lang.Boolean.TRUE.toString())

    val jlineTerminal = TerminalBuilder.builder().jna(true).build()
    val completer = new Completion(completion)
    val parser    = new ReplParser(repl)
    val history   = new DefaultHistory

    val builder =
      LineReaderBuilder.builder()
      .appName("scala")
      .completer(completer)
      .history(history)
      .parser(parser)
      .terminal(jlineTerminal)

    locally {
      import LineReader._, Option._
      builder
        .option(AUTO_GROUP, false)
        .option(LIST_PACKED, true)  // TODO
        .option(INSERT_TAB, true)   // At the beginning of the line, insert tab instead of completing
        .variable(HISTORY_FILE, config.historyFile) // Save history to file
        .variable(SECONDARY_PROMPT_PATTERN, config.encolor(config.continueText)) // Continue prompt
        .variable(WORDCHARS, LineReaderImpl.DEFAULT_WORDCHARS.filterNot("*?.[]~=/&;!#%^(){}<>".toSet))
        .option(Option.DISABLE_EVENT_EXPANSION, true) // Otherwise `scala> println(raw"\n".toList)` gives `List(n)` !!
        .option(Option.COMPLETE_MATCHER_CAMELCASE, true)
    }
    object customCompletionMatcher extends CompletionMatcherImpl {
      override def compile(options: util.Map[LineReader.Option, lang.Boolean], prefix: Boolean, line: CompletingParsedLine, caseInsensitive: Boolean, errors: Int, originalGroupName: String): Unit = {
        val errorsReduced = line.wordCursor() match {
          case 0 | 1 | 2 | 3 => 0 // disable JLine's levenshtein-distance based typo matcher for short strings
          case 4 | 5 => math.max(errors, 1)
          case _ => errors
        }
        super.compile(options, prefix, line, caseInsensitive, errorsReduced, originalGroupName)

        // TODO JLINE All of this can/must be removed after the next JLine upgrade
        matchers.remove(matchers.size() - 2) // remove ty
        val wd = line.word();
        val wdi = if (caseInsensitive) wd.toLowerCase() else  wd
        val typoMatcherWord = if (prefix) wdi.substring(0, line.wordCursor()) else wdi
        val fixedTypoMatcher = typoMatcher(
          typoMatcherWord,
          errorsReduced,
          !caseInsensitive, // Fixed in JLine https://github.com/jline/jline3/pull/647, remove the negation when upgrading!
          originalGroupName
        )
        matchers.add(matchers.size - 2, fixedTypoMatcher)
      }

      override def matches(candidates: JList[Candidate]): JList[Candidate] = {
        val matching = super.matches(candidates)
        matching
      }
    }

    builder.completionMatcher(customCompletionMatcher)

    val reader = builder.build()

    val desc: java.util.function.Function[CmdLine, CmdDesc] = (cmdLine) => new CmdDesc(util.Arrays.asList(new AttributedString("demo")), Collections.emptyList(), Collections.emptyMap())
    new TailTipWidgets(reader, desc, 1, TipType.COMPLETER)
    val keyMap = reader.getKeyMaps.get("main")

    object ScalaShowType {
      val Name = "scala-show-type"
      private var lastInvokeLocation: Option[(String, Int)] = None
      def apply(): Boolean = {
        val nextInvokeLocation = Some((reader.getBuffer.toString, reader.getBuffer.cursor()))
        val cursor = reader.getBuffer.cursor()
        val text   = reader.getBuffer.toString
        val result = completer.complete(text, cursor, filter = true)
        if (lastInvokeLocation == nextInvokeLocation) {
          showTree(result)
          lastInvokeLocation = None
        } else {
          showType(result)
          lastInvokeLocation = nextInvokeLocation
        }
        true
      }
      def showType(result: shell.CompletionResult): Unit = {
        reader.getTerminal.writer.println()
        reader.getTerminal.writer.println(result.typeAtCursor)
        reader.callWidget(LineReader.REDRAW_LINE)
        reader.callWidget(LineReader.REDISPLAY)
        reader.getTerminal.flush()
      }
      def showTree(result: shell.CompletionResult): Unit = {
        reader.getTerminal.writer.println()
        reader.getTerminal.writer.println(Naming.unmangle(result.typedTree))
        reader.callWidget(LineReader.REDRAW_LINE)
        reader.callWidget(LineReader.REDISPLAY)
        reader.getTerminal.flush()
      }
    }
    reader.getWidgets().put(ScalaShowType.Name, () => ScalaShowType())

    locally {
      import LineReader._
      // VIINS, VICMD, EMACS
      val keymap = if (config.viMode) VIINS else EMACS
      reader.getKeyMaps.put(MAIN, reader.getKeyMaps.get(keymap));
      keyMap.bind(new Reference(ScalaShowType.Name), KeyMap.ctrl('T'))
    }
    def secure(p: java.nio.file.Path): Unit = {
      try scala.reflect.internal.util.OwnerOnlyChmod.chmodFileOrCreateEmpty(p)
      catch { case scala.util.control.NonFatal(e) =>
        if (config.isReplDebug) e.printStackTrace()
        config.replinfo(s"Warning: history file ${p}'s permissions could not be restricted to owner-only.")
      }
    }
    def backupHistory(): Unit = {
      import java.nio.file.{Files, Paths, StandardCopyOption}, StandardCopyOption.REPLACE_EXISTING
      val hf = Paths.get(config.historyFile)
      val bk = Paths.get(config.historyFile + ".bk")
      Files.move(/*source =*/ hf, /*target =*/ bk, REPLACE_EXISTING)
      secure(bk)
    }
    // always try to restrict permissions on history file,
    // creating an empty file if none exists.
    secure(java.nio.file.Paths.get(config.historyFile))
    try history.attach(reader)
    catch {
      case e: IllegalArgumentException if e.getMessage.contains("Bad history file syntax") =>
        backupHistory()
        history.attach(reader)
      case _: NumberFormatException =>
        backupHistory()
        history.attach(reader)
    }
    new Reader(config, reader, accumulator, completer, jlineTerminal)
  }

  class ReplParser(repl: Repl) extends Parser {
    val scalaParser = new ScalaParser(repl)
    val commandParser = new CommandParser(repl)
    def parse(line: String, cursor: Int, context: ParseContext): ParsedLine =
      if (line.startsWith(":")) commandParser.parse(line, cursor, context)
      else scalaParser.parse(line, cursor, context)
  }
  class ScalaParser(repl: Repl) extends Parser {
    import Results._

    def parse(line: String, cursor: Int, context: ParseContext): ParsedLine = {
      import ParseContext._
      context match {
        case ACCEPT_LINE =>
          repl.parseString(line) match {
            case Incomplete if line.endsWith("\n\n") => throw new SyntaxError(0, 0, "incomplete") // incomplete but we're bailing now
            case Incomplete                          => throw new EOFError(0, 0, "incomplete")    // incomplete so keep reading input
            case Success | Error                     => tokenize(line, cursor) // Try a real "final" parse. (dnw: even for Error??)
          }
        case COMPLETE => tokenize(line, cursor)    // Parse to find completions (typically after a Tab).
        case SECONDARY_PROMPT =>
          tokenize(line, cursor) // Called when we need to update the secondary prompts.
        case SPLIT_LINE | UNSPECIFIED =>
          ScalaParsedLine(line, cursor, 0, 0, Nil)
      }
    }
    private def tokenize(line: String, cursor: Int): ScalaParsedLine = {
      val tokens = repl.reporter.suppressOutput {
        repl.tokenize(line)
      }
      repl.reporter.reset()
      if (tokens.isEmpty) ScalaParsedLine(line, cursor, 0, 0, Nil)
      else {
        val current = tokens.find(t => t.start <= cursor && cursor <= t.end)
        val (wordCursor, wordIndex) = current match {
          case Some(t) if t.isIdentifier =>
            (cursor - t.start, tokens.indexOf(t))
          case Some(t)  =>
            val isIdentifierStartKeyword = (t.start until t.end).forall(i => Chars.isIdentifierPart(line.charAt(i)))
            if (isIdentifierStartKeyword)
              (cursor - t.start, tokens.indexOf(t))
            else
              (0, -1)
          case _ =>
            (0, -1)
        }
        ScalaParsedLine(line, cursor, wordCursor, wordIndex, tokens)
      }
    }
  }
  class CommandParser(repl: Repl) extends Parser {
    val defaultParser = new DefaultParser()
    def parse(line: String, cursor: Int, context: ParseContext): ParsedLine =
      defaultParser.parse(line, cursor, context)
  }

  /**
   * Lines of Scala are opaque to JLine.
   *
   * @param line the line
   */
  case class ScalaParsedLine(line: String, cursor: Int, wordCursor: Int, wordIndex: Int, tokens: List[TokenData]) extends CompletingParsedLine {
    require(wordIndex <= tokens.size,
      s"wordIndex $wordIndex out of range ${tokens.size}")
    require(wordIndex == -1 || wordCursor == 0 || wordCursor <= tokens(wordIndex).end - tokens(wordIndex).start,
      s"wordCursor $wordCursor should be in range ${tokens(wordIndex)}")
    // Members declared in org.jline.reader.CompletingParsedLine.
    // This is where backticks could be added, for example.
    def escape(candidate: CharSequence, complete: Boolean): CharSequence = candidate
    def rawWordCursor: Int = wordCursor
    def rawWordLength: Int = word.length
    def word: String =
      if (wordIndex == -1 || wordIndex == tokens.size)
        ""
      else {
        val t = tokens(wordIndex)
        line.substring(t.start, t.end)
      }
    def words: JList[String] = {
      import scala.jdk.CollectionConverters._
      tokens.map(t => line.substring(t.start, t.end)).asJava
    }
  }

  private def initLogging(trace: Boolean): Unit = {
    import java.util.logging._
    val logger  = Logger.getLogger("org.jline")
    val handler = new ConsoleHandler()
    val level   = if (trace) Level.FINEST else Level.FINE
    logger.setLevel(level)
    handler.setLevel(level)
    logger.addHandler(handler)
  }
}

/** A Completion bridge to JLine3.
 *  It delegates both interfaces to an underlying `Completion`.
 */
class Completion(delegate: shell.Completion) extends shell.Completion with Completer {
  var lastPrefix: String = ""
  require(delegate != null)
  // REPL Completion
  def complete(buffer: String, cursor: Int, filter: Boolean): shell.CompletionResult = delegate.complete(buffer, cursor, filter)

  // JLine Completer
  def complete(lineReader: LineReader, parsedLine: ParsedLine, newCandidates: JList[Candidate]): Unit = {
    def candidateForResult(cc: CompletionCandidate): Candidate = {
      val value = cc.name
      val displayed = cc.name + (cc.arity match {
        case CompletionCandidate.Nullary => ""
        case CompletionCandidate.Nilary => "()"
        case _ => "("
      })
      val group = null        // results may be grouped
      val descr =             // displayed alongside
        if (cc.isDeprecated) "deprecated"
        else if (cc.isUniversal) "universal"
        else null
      val suffix = null       // such as slash after directory name
      val key = null          // same key implies mergeable result
      val complete = false    // more to complete?
      new Candidate(value, displayed, group, descr, suffix, key, complete)
    }
    val result = complete(parsedLine.line, parsedLine.cursor, filter = false)
    for (cc <- result.candidates)
      newCandidates.add(candidateForResult(cc))

    val parsedLineWord = parsedLine.word()
    result.candidates.filter(_.name == parsedLineWord) match {
      case Nil =>
      case exacts =>
        val declStrings = exacts.map(_.declString()).filterNot(_ == "")
        if (declStrings.nonEmpty) {
          lineReader.getTerminal.writer.println()
          for (declString <- declStrings)
            lineReader.getTerminal.writer.println(declString)
          lineReader.callWidget(LineReader.REDRAW_LINE)
          lineReader.callWidget(LineReader.REDISPLAY)
          lineReader.getTerminal.flush()
        }
    }
  }
}

// TODO
class HistoryAdaptor(history: History) extends shell.History {
  //def historicize(text: String): Boolean = false

  def asStrings: List[String] = Nil
  //def asStrings(from: Int, to: Int): List[String] = asStrings.slice(from, to)
  def index: Int = 0
  def size: Int = 0
}
