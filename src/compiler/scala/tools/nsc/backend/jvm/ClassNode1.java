package scala.tools.nsc.backend.jvm;

import scala.tools.asm.MethodVisitor;
import scala.tools.asm.Opcodes;
import scala.tools.asm.tree.ClassNode;
import scala.tools.asm.tree.MethodNode;

public class ClassNode1 extends ClassNode {
    public ClassNode1() {
        this(Opcodes.ASM6);
    }

    public ClassNode1(int api) {
        super(api);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodNode method = new MethodNode1(access, name, descriptor, signature, exceptions);
        methods.add(method);
        return method;
    }
}
