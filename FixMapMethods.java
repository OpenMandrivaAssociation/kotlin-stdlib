import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Drop illegal Kotlin property names and the duplicate read-only Map bridges. */
public final class FixMapMethods {
    public static void main(String[] args) throws Exception {
        int changed = 0;
        for (String name : args) {
            Path path = Path.of(name);
            byte[] data = Files.readAllBytes(path);
            ClassReader reader = new ClassReader(data);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            int before = node.methods.size();
            List<MethodNode> kept = dedupe(node.methods);
            if (kept.size() == before && same(node.methods, kept)) {
                System.out.println("UNCHANGED " + node.name);
                continue;
            }
            node.methods = kept;
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] out = writer.toByteArray();
            Files.write(path, out);
            changed++;
            System.out.println("FIXED " + node.name + " methods " + before + " -> " + kept.size());
        }
        System.out.println("FIX_COUNT " + changed);
    }

    private static boolean same(List<MethodNode> left, List<MethodNode> right) {
        return left.equals(right);
    }

    private static List<MethodNode> dedupe(List<MethodNode> methods) {
        Map<String, MethodNode> chosen = new LinkedHashMap<String, MethodNode>();
        for (MethodNode method : methods) {
            if (illegal(method.name)) {
                continue;
            }
            String key = method.name + method.desc;
            MethodNode previous = chosen.get(key);
            if (previous == null || prefer(method, previous)) {
                chosen.put(key, method);
            }
        }
        return new ArrayList<MethodNode>(chosen.values());
    }

    private static boolean illegal(String name) {
        return name.indexOf('<') >= 0
                && !"<init>".equals(name)
                && !"<clinit>".equals(name);
    }

    /** True when candidate should replace the method already chosen. */
    private static boolean prefer(MethodNode candidate, MethodNode current) {
        boolean candidateThrows = throwsReadOnly(candidate);
        boolean currentThrows = throwsReadOnly(current);
        if (candidateThrows != currentThrows) {
            return !candidateThrows;
        }
        boolean candidateFinal = (candidate.access & Opcodes.ACC_FINAL) != 0;
        boolean currentFinal = (current.access & Opcodes.ACC_FINAL) != 0;
        if (candidateFinal != currentFinal) {
            return candidateFinal;
        }
        return false;
    }

    private static boolean throwsReadOnly(MethodNode method) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getNext();
        }
        if (!(insn instanceof TypeInsnNode) || insn.getOpcode() != Opcodes.NEW) {
            return false;
        }
        return "java/lang/UnsupportedOperationException".equals(((TypeInsnNode) insn).desc);
    }
}
