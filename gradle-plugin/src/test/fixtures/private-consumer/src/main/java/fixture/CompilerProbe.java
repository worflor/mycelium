package fixture;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public final class CompilerProbe {
    public static void main(String[] args) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        if (node.recordComponents != null) throw new AssertionError("unexpected record components");
        try (java.io.InputStream input = CompilerProbe.class.getResourceAsStream("CompilerProbe.class")) {
            new ClassReader(input).accept(node, 0);
        }
        if (!node.name.equals("fixture/CompilerProbe")) throw new AssertionError(node.name);
        if (!ClassReader.class.getName().equals("fixture.privateapi.asm.ClassReader")) {
            throw new AssertionError("feature library escaped its namespace");
        }
        if (!System.getProperty("java.specification.version").equals("1.8")) {
            throw new AssertionError("fixture must execute on Java 8");
        }
        System.out.println("PASS: ASM 9 compiles ahead of platform ASM 4 and runs privately on Java 8");
    }
}
