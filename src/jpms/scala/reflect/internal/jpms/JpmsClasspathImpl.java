package scala.reflect.internal.jpms;

import javax.lang.model.SourceVersion;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.lang.module.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JpmsClasspathImpl {

    // has to be a valid module name, so we can't use the pseudo-name "ALL-UNNAMED"
    static final String UNNAMED_MODULE_NAME = "___UNNAMED";
    private static final String ALL_UNNAMED = "ALL-UNNAMED";

    private static final Pattern ADD_REQUIRES_PATTERN = Pattern.compile("([^=]+)=(,*[^,].*)");
    private static final Pattern ADD_EXPORTS_PATTERN = Pattern.compile("([^/]+)/([^=]+)=(,*[^,].*)");
    private final StandardJavaFileManager fileManager;
    private final List<String> addReads;
    private final List<String> addExports;
    private final List<String> addModules;
    private final Path output;
    private ModuleReference classOutputModuleRef = null;
    private final ModuleFinder moduleFinder;
    private String uniquePatchModule;

    public JpmsClasspathImpl(Optional<String> release, Path output, List<List<String>> fileManangerOptions, List<String> addModules,
                             List<String> addExports, List<String> addReads, String uniquePatchModule) throws IOException {
        this.uniquePatchModule = uniquePatchModule;
        List<List<String>> unhandled = new ArrayList<>();
        Consumer<StandardJavaFileManager> c = (fm -> {
            try {
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Collections.singleton(output));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            for (List<String> optionSubList: fileManangerOptions) {
                Iterator<String> iterator = optionSubList.iterator();
                while (iterator.hasNext()) {
                    String next = iterator.next();
                    boolean handled = fm.handleOption(next, iterator);
                    if (!handled) {
                        unhandled.add(optionSubList);
                        break;
                    }
                }
            }
        });
        ModuleFinderAndFileManager moduleFinderAndFileManager = ModuleFinderAndFileManager.get(release, c);
        fileManager = moduleFinderAndFileManager.fileManager();

        moduleFinder = moduleFinderAndFileManager.moduleFinder();
        this.addReads = addReads;
        this.addExports = addExports;
        this.addModules = addModules;
        this.output = output;

        if (!unhandled.isEmpty()) throw new IllegalArgumentException("Some options were unhandled: " + unhandled);
    }

    public ResolvedModuleGraph resolveModuleGraph(JpmsModuleDescriptor sourceModule) throws IOException {
        ModuleDescriptor sourceModuleDescriptor = null;
        if (sourceModule != null) {
            sourceModuleDescriptor = sourceModule.create();
        }
        List<ModuleDescriptor> sourceModules = Optional.ofNullable(sourceModuleDescriptor).stream().collect(Collectors.toList());
        ArrayList<ModuleDescriptor> sourceAndUnnamed = new ArrayList<>(sourceModules);
        sourceAndUnnamed.add(ModuleDescriptor.newModule(UNNAMED_MODULE_NAME).requires("java.base").build());
        ModuleFinder fromSourceFinder = FixedModuleFinder.newModuleFinder(sourceAndUnnamed);
        ModuleFinder finder = fromSourceFinder;
        String defaultModule = null;
        if (sourceModule == null) {
            // Consider a module-info.class from the class output directory if there is not module-info.java among the compiled sources.
            JavaFileObject module = fileManager.getJavaFileForInput(StandardLocation.CLASS_OUTPUT, "module-info", JavaFileObject.Kind.CLASS);
            if (module != null) {
                ModuleFinder classOutputModuleFinder = ModuleFinder.of(this.output);
                Set<ModuleReference> classOutputModules = classOutputModuleFinder.findAll();
                if (classOutputModules.size() != 1) {
                    throw new IllegalStateException("Expected one module-info.class in " + this.output);
                }
                finder = ModuleFinder.compose(classOutputModuleFinder, finder);
            }
        } else {
            defaultModule = sourceModuleDescriptor.name();
        }
        if (uniquePatchModule != null) {
            if (defaultModule != null)
                throw new IllegalArgumentException("Cannot provide both `--patch-module` and a module-info source of class file"); // TODO JMPS revisit this
            else defaultModule = uniquePatchModule;
        }
        ExportRequireAdder adder = getExportRequireAdder(this.addExports, this.addReads);
        // Resolve the module graph.
        // `fromSourceFinder` is passed as the `before` finder to take precendence over, rather than clash with, a module-info.class in the
        // output directory.
        ExportRequireAddingModuleFinder afterFinder = new ExportRequireAddingModuleFinder(this.moduleFinder, adder);
        List<String> roots = new ArrayList<>(this.addModules);
        roots.add(UNNAMED_MODULE_NAME);
        if (defaultModule == null) {
            defaultModule = "";
        } else {
            roots.add(defaultModule);
        }
        Configuration configuration = Configuration.empty().resolve(finder, afterFinder, roots);
        return new ResolvedModuleGraph(defaultModule, configuration);
    }

    private ExportRequireAdder getExportRequireAdder(List<String> addExports, List<String> addReads) {
        HashMap<String, ModuleDescriptor.Exports> addExportsMap = processAddExports(addExports);
        HashMap<String, List<String>> addRequires = processAddReads(addReads);
        return new ExportRequireAdder() {
            @Override
            public Iterable<ModuleDescriptor.Exports> addExports(String moduleName) {
                ModuleDescriptor.Exports exports = addExportsMap.get(moduleName);
                if (exports == null) {
                    return Collections.emptyList();
                } else {
                    return Collections.singleton(exports);
                }
            }

            @Override
            public Iterable<String> addReads(String moduleName) {
                return addRequires.getOrDefault(moduleName, Collections.emptyList());
            }
        };
    }

    private HashMap<String, ModuleDescriptor.Exports> processAddExports(List<String> addExports) {
        HashMap<String, ModuleDescriptor.Exports> addExportsMap = new HashMap<>();
        for (String addExport: addExports) {
            Matcher matcher = ADD_EXPORTS_PATTERN.matcher(addExport);
            if (!matcher.matches())
                throw new IllegalArgumentException("Invalid -addexports specification: " + addExport);
            String moduleName = matcher.group(1);
            if (!SourceVersion.isName(moduleName))
                throw new IllegalArgumentException("Invalid module name in " + addExport);
            String packageName = matcher.group(2);
            if (!SourceVersion.isName(packageName))
                throw new IllegalArgumentException("Invalid package name in " + addExport);
            HashSet<String> targets = new HashSet<>();
            for (String targetName: matcher.group(3).split(",")) {
                if (targetName.equals("ALL-UNNAMED")) {
                    targets.add(UNNAMED_MODULE_NAME);
                } else {
                    targets.add(targetName);
                }
            }
            addExportsMap.put(moduleName, mkExports(packageName, targets));
        }
        return addExportsMap;
    }

    private HashMap<String, List<String>> processAddReads(List<String> addRequires) {
        HashMap<String, List<String>> result = new HashMap<>();
        for (String addRequire: addRequires) {
            Matcher matcher = ADD_REQUIRES_PATTERN.matcher(addRequire);
            if (!matcher.matches())
                throw new IllegalArgumentException("Invalid -addrequire specification: " + addRequire);
            String moduleName = matcher.group(1);
            if (!SourceVersion.isName(moduleName))
                throw new IllegalArgumentException("Invalid module name in " + addRequire);
            List<String> requires = new ArrayList<>();
            for (String required: matcher.group(2).split(",")) {
                if (required.equals(ALL_UNNAMED)) {
                    requires.add(UNNAMED_MODULE_NAME);
                } else {
                    requires.add(required);
                }
            }
            result.put(moduleName, requires);
        }
        return result;
    }

    private static ModuleDescriptor.Exports mkExports(String packageName, HashSet<String> targets) {
        return ModuleDescriptor.newModule("dummy").exports(Set.of(), packageName, targets).build().exports().iterator().next();
    }

    public StandardJavaFileManager getFileManager() {
        return fileManager;
    }

    public String currentModuleName() {
        if (classOutputModuleRef == null) {
            return "";
        } else {
            return classOutputModuleRef.descriptor().name();
        }
    }
}
