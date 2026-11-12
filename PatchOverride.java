import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Skip FirOverrideChecker.reportNothingToOverride when the member has no source.
 * kotlinc 2.0.21 throws "source must not be null" there while compiling the
 * Kotlin 2.4.21 stdlib builtins. Members that have a source still report.
 */
public final class PatchOverride implements Opcodes {
    private static final String CLASS =
            "org/jetbrains/kotlin/fir/analysis/checkers/declaration/FirOverrideChecker.class";
    private static final String SCOPE_CLASS =
            "org/jetbrains/kotlin/fir/scopes/impl/FirIntegerConstantOperatorScope.class";
    private static final String SCOPE_METHOD = "processFunctionsByName";
    private static final String SCOPE_DESC =
            "(Lorg/jetbrains/kotlin/name/Name;Lkotlin/jvm/functions/Function1;)V";
    private static final String FAKE_CLASS =
            "org/jetbrains/kotlin/fir/scopes/impl/FirFakeOverrideGenerator.class";
    private static final String STATUS_DESC =
            "(Lorg/jetbrains/kotlin/fir/declarations/FirCallableDeclaration;)V";
    private static final String METHOD = "reportNothingToOverride";
    private static final String DESC =
            "(Lorg/jetbrains/kotlin/diagnostics/DiagnosticReporter;"
                    + "Lorg/jetbrains/kotlin/fir/symbols/impl/FirCallableSymbol;"
                    + "Lorg/jetbrains/kotlin/fir/analysis/checkers/context/CheckerContext;)V";
    private static final String SINCE_PROVIDER =
            "org/jetbrains/kotlin/fir/declarations/SinceKotlinProvider.class";
    private static final String SINCE_HELPERS =
            "org/jetbrains/kotlin/fir/analysis/checkers/FirSinceKotlinHelpersKt.class";
    private static final String DEPRECATION_DESC =
            "(Lorg/jetbrains/kotlin/config/LanguageVersionSettings;)"
                    + "Lorg/jetbrains/kotlin/fir/declarations/FirDeprecationInfo;";
    private static final String SINCE_CHECK_DESC =
            "(Lorg/jetbrains/kotlin/fir/declarations/FirDeclaration;"
                    + "Lorg/jetbrains/kotlin/fir/analysis/checkers/context/CheckerContext;)"
                    + "Lorg/jetbrains/kotlin/fir/analysis/checkers/FirSinceKotlinAccessibility;";
    private static final String EXPECT_ACTUAL =
            "org/jetbrains/kotlin/resolve/calls/mpp/AbstractExpectActualChecker.class";
    private static final String FAKE_RESOLVER =
            "org/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver.class";
    private static final String COLLECT_CLASS =
            "org/jetbrains/kotlin/backend/common/actualizer/"
                    + "SpecialFakeOverrideSymbolsResolver$collectOverrides$1.class";
    private static final String COLLECT_OWNER =
            "org/jetbrains/kotlin/backend/common/actualizer/"
                    + "SpecialFakeOverrideSymbolsResolver$collectOverrides$1";
    private static final String CONST_TRANSFORMER =
            "org/jetbrains/kotlin/ir/interpreter/transformer/IrConstOnlyNecessaryTransformer.class";
    private static final String FIR_PIPELINE =
            "org/jetbrains/kotlin/fir/pipeline/Fir2IrPipeline.class";
    private static final String RESOLVER_VISITOR =
            "org/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolverVisitor.class";
    private static final String MAPPED_ENUM =
            "org/jetbrains/kotlin/backend/jvm/lower/MappedEnumWhenLowering.class";
    private static final String DIAG_CLASS = "StdlibDiag.class";
    private static final String RESOLVER_OWNER =
            "org/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver";
    private static final String MODALITY_DESC =
            "(Lorg/jetbrains/kotlin/descriptors/Modality;Lorg/jetbrains/kotlin/descriptors/Modality;"
                    + "Lorg/jetbrains/kotlin/descriptors/Modality;Lorg/jetbrains/kotlin/descriptors/Modality;)Z";

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: PatchOverride input.jar output.jar StdlibDiag.class");
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path diagClass = Path.of(args[2]);
        boolean patched = false;
        boolean scopePatched = false;
        boolean statusPatched = false;
        boolean skipStatus = true;
        boolean sinceHiddenPatched = false;
        boolean sinceCheckPatched = false;
        boolean modalityPatched = false;
        boolean fakeResolverPatched = false;
        boolean collectPatched = false;
        boolean constPatched = false;
        boolean pipelinePatched = false;
        boolean mappedEnumPatched = false;
        boolean inlinePatched = false;
        boolean fileCodegenPatched = false;
        boolean flattenPatched = false;
        boolean emptyOverridePatched = false;
        boolean clashPatched = false;
        try (JarFile jar = new JarFile(in.toFile());
                OutputStream raw = Files.newOutputStream(out);
                JarOutputStream jos = new JarOutputStream(raw)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                JarEntry copy = new JarEntry(entry.getName());
                jos.putNextEntry(copy);
                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] data = is.readAllBytes();
                    if (entry.getName().equals(CLASS)) {
                        data = patch(data);
                        patched = true;
                    } else if (entry.getName().equals(SCOPE_CLASS)) {
                        data = patchScope(data);
                        scopePatched = true;
                    } else if (!skipStatus && entry.getName().equals(FAKE_CLASS)) {
                        data = patchStatus(data);
                        statusPatched = true;
                    } else if (entry.getName().equals(SINCE_PROVIDER)) {
                        data = dropFirstIfgt("computeDeprecationInfo", DEPRECATION_DESC, data);
                        sinceHiddenPatched = true;
                    } else if (entry.getName().equals(SINCE_HELPERS)) {
                        data = dropFirstIfgt(
                                "checkSinceKotlinVersionAccessibility", SINCE_CHECK_DESC, data);
                        sinceCheckPatched = true;
                    } else if (entry.getName().equals(EXPECT_ACTUAL)) {
                        data = patchModality(data);
                        modalityPatched = true;
                    } else if (entry.getName().equals(FAKE_RESOLVER)) {
                        data = patchFakeResolver(data);
                        fakeResolverPatched = true;
                    } else if (entry.getName().equals(COLLECT_CLASS)) {
                        data = patchCollectOverrides(data);
                        collectPatched = true;
                    } else if (entry.getName().equals(CONST_TRANSFORMER)) {
                        data = patchConstTransformer(data);
                        constPatched = true;
                    } else if (entry.getName().equals(FIR_PIPELINE)) {
                        data = patchPipeline(data);
                        pipelinePatched = true;
                    } else if (entry.getName().equals(MAPPED_ENUM)) {
                        data = patchMappedEnum(data);
                        mappedEnumPatched = true;
                    } else if (entry.getName().equals(
                            "org/jetbrains/kotlin/backend/jvm/MemoizedValueClassAbstractReplacements.class")) {
                        data = patchInlineReplacements(data);
                        inlinePatched = true;
                    } else if (entry.getName().equals("org/jetbrains/kotlin/backend/jvm/FileCodegen.class")) {
                        data = patchFileCodegen(data);
                        fileCodegenPatched = true;
                    } else if (entry.getName().equals(
                            "org/jetbrains/kotlin/backend/common/lower/FlattenStringConcatenationLowering$Companion.class")) {
                        data = patchFlatten(data);
                        flattenPatched = true;
                    } else if (entry.getName().equals(
                            "org/jetbrains/kotlin/backend/jvm/ir/JvmIrUtilsKt.class")) {
                        data = patchFindSuper(data);
                        emptyOverridePatched = true;
                    } else if (entry.getName().equals(
                            "org/jetbrains/kotlin/backend/jvm/codegen/JvmMethodSignatureClashDetector.class")) {
                        data = patchClashDetector(data);
                        clashPatched = true;
                    }
                    jos.write(data);
                }
                jos.closeEntry();
            }
            JarEntry diag = new JarEntry(DIAG_CLASS);
            jos.putNextEntry(diag);
            jos.write(Files.readAllBytes(diagClass));
            jos.closeEntry();
        }
        if (skipStatus) {
            statusPatched = true;
        }
        if (!patched || !scopePatched || !statusPatched
                || !sinceHiddenPatched || !sinceCheckPatched || !modalityPatched
                || !fakeResolverPatched || !collectPatched || !constPatched || !pipelinePatched
                || !mappedEnumPatched || !inlinePatched || !fileCodegenPatched
                || !flattenPatched || !emptyOverridePatched || !clashPatched) {
            throw new IllegalStateException(
                    "class not found override=" + patched
                            + " scope=" + scopePatched
                            + " status=" + statusPatched
                            + " sinceHidden=" + sinceHiddenPatched
                            + " sinceCheck=" + sinceCheckPatched
                            + " modality=" + modalityPatched
                            + " fakeResolver=" + fakeResolverPatched
                            + " collect=" + collectPatched
                            + " const=" + constPatched
                            + " pipeline=" + pipelinePatched
                            + " mappedEnum=" + mappedEnumPatched
                            + " inline=" + inlinePatched
                            + " fileCodegen=" + fileCodegenPatched
                            + " flatten=" + flattenPatched
                            + " emptyOverride=" + emptyOverridePatched
                            + " clash=" + clashPatched);
        }
    }

    /** Print the unbound fake-override signature before the stdlib IR actualizer aborts. */
    private static byte[] patchFakeResolver(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"getReferencedSimpleFunction".equals(method.name)) {
                continue;
            }
            insertRedirectLookup(method);
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof LdcInsnNode) || !"No override for ".equals(((LdcInsnNode) insn).cst)) {
                    continue;
                }
                AbstractInsnNode site = insn;
                while (site != null
                        && !(site instanceof TypeInsnNode
                                && site.getOpcode() == NEW
                                && "java/lang/IllegalStateException".equals(((TypeInsnNode) site).desc))) {
                    site = site.getPrevious();
                }
                if (site == null) {
                    throw new IllegalStateException("fake override throw site not found");
                }
                LabelNode returnLabel = new LabelNode();
                AbstractInsnNode ret = null;
                for (AbstractInsnNode cursor = method.instructions.getFirst(); cursor != null; cursor = cursor.getNext()) {
                    if (cursor.getOpcode() == ARETURN) {
                        ret = cursor;
                        break;
                    }
                }
                if (ret == null) {
                    throw new IllegalStateException("fake override return not found");
                }
                AbstractInsnNode retValue = ret.getPrevious();
                while (retValue != null && retValue.getOpcode() < 0) {
                    retValue = retValue.getPrevious();
                }
                if (retValue == null || retValue.getOpcode() != CHECKCAST) {
                    throw new IllegalStateException("fake override return value not found");
                }
                method.instructions.insertBefore(retValue, returnLabel);
                LabelNode doThrow = new LabelNode();
                InsnList bypass = new InsnList();
                bypass.add(new VarInsnNode(ALOAD, 6));
                bypass.add(new MethodInsnNode(
                        INVOKEINTERFACE, "org/jetbrains/kotlin/ir/symbols/IrSymbol", "isBound", "()Z", true));
                bypass.add(new JumpInsnNode(IFNE, doThrow));
                bypass.add(cappedFosig());
                bypass.add(new VarInsnNode(ALOAD, 3));
                bypass.add(new JumpInsnNode(GOTO, returnLabel));
                bypass.add(doThrow);
                method.instructions.insertBefore(site, bypass);
                method.instructions.insertBefore(site, fakeSignaturePrint());
                patched = true;
                break;
            }
        }
        if (!patched) {
            throw new IllegalStateException("fake override message not found");
        }
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "fosigPrints", "I", null, null));
        addNoteIr(node);
        addNoteCollect(node);
        addSkipUnbound(node);
        addRedirectMap(node);
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * collectOverrides inlines remap and throws before getReferencedSimpleFunction
     * reaches its own unbound-symbol bypass. An unbound original has no IR owner.
     * Record that the current member overrides it, then keep walking.
     */
    private static byte[] patchCollectOverrides(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"invokeSuspend".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof LdcInsnNode) || !"No override for ".equals(((LdcInsnNode) insn).cst)) {
                    continue;
                }
                AbstractInsnNode site = insn;
                while (site != null
                        && !(site instanceof TypeInsnNode
                                && site.getOpcode() == NEW
                                && "java/lang/IllegalStateException".equals(((TypeInsnNode) site).desc))) {
                    site = site.getPrevious();
                }
                if (site == null) {
                    throw new IllegalStateException("collectOverrides throw site not found");
                }
                LabelNode doThrow = new LabelNode();
                InsnList bypass = new InsnList();
                // The throw sits on two dead stack values (SequenceScope, resolver).
                // A back-edge to the override loop does not merge in ASM. Pop them,
                // remember this member as the override, and end this walk.
                bypass.add(new VarInsnNode(ALOAD, 9));
                bypass.add(new MethodInsnNode(
                        INVOKEINTERFACE, "org/jetbrains/kotlin/ir/symbols/IrSymbol", "isBound", "()Z", true));
                bypass.add(new JumpInsnNode(IFNE, doThrow));
                bypass.add(new InsnNode(POP));
                bypass.add(new InsnNode(POP));
                bypass.add(new VarInsnNode(ALOAD, 0));
                bypass.add(new FieldInsnNode(
                        GETFIELD,
                        COLLECT_OWNER,
                        "$this_collectOverrides",
                        "Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"));
                bypass.add(new VarInsnNode(ALOAD, 6));
                bypass.add(new MethodInsnNode(
                        INVOKESTATIC,
                        RESOLVER_OWNER,
                        "noteCollect",
                        "(Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"
                                + "Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                        false));
                bypass.add(new VarInsnNode(ALOAD, 0));
                bypass.add(new FieldInsnNode(
                        GETFIELD,
                        COLLECT_OWNER,
                        "this$0",
                        "Lorg/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver;"));
                bypass.add(new MethodInsnNode(
                        INVOKESTATIC,
                        RESOLVER_OWNER,
                        "access$getCachedFakeOverrides$p",
                        "(Lorg/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver;)"
                                + "Ljava/util/Map;",
                        false));
                bypass.add(new VarInsnNode(ALOAD, 8));
                bypass.add(new VarInsnNode(ALOAD, 9));
                bypass.add(new MethodInsnNode(
                        INVOKESTATIC,
                        "kotlin/TuplesKt",
                        "to",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Lkotlin/Pair;",
                        false));
                bypass.add(new VarInsnNode(ALOAD, 0));
                bypass.add(new FieldInsnNode(
                        GETFIELD,
                        COLLECT_OWNER,
                        "$this_collectOverrides",
                        "Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"));
                bypass.add(new MethodInsnNode(
                        INVOKEINTERFACE,
                        "org/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration",
                        "getSymbol",
                        "()Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;",
                        true));
                bypass.add(new MethodInsnNode(
                        INVOKEINTERFACE,
                        "java/util/Map",
                        "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        true));
                bypass.add(new InsnNode(POP));
                bypass.add(new VarInsnNode(ALOAD, 9));
                bypass.add(new VarInsnNode(ALOAD, 0));
                bypass.add(new FieldInsnNode(
                        GETFIELD,
                        COLLECT_OWNER,
                        "$this_collectOverrides",
                        "Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"));
                bypass.add(new MethodInsnNode(
                        INVOKEINTERFACE,
                        "org/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration",
                        "getSymbol",
                        "()Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;",
                        true));
                bypass.add(new MethodInsnNode(
                        INVOKESTATIC,
                        RESOLVER_OWNER,
                        "redirectPut",
                        "(Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                        false));
                bypass.add(new FieldInsnNode(GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;"));
                bypass.add(new InsnNode(ARETURN));
                bypass.add(doThrow);
                method.instructions.insertBefore(site, bypass);
                patched = true;
                break;
            }
            if (!patched) {
                throw new IllegalStateException("collectOverrides message not found");
            }
            boolean ownerPatched = false;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!"getOwner".equals(call.name)
                        || !"org/jetbrains/kotlin/ir/symbols/IrSymbol".equals(call.owner)) {
                    continue;
                }
                LabelNode cont = new LabelNode();
                InsnList skip = new InsnList();
                // Stack is [SequenceScope, resolver, symbol]. Non-fake overridden
                // symbols are assumed bound. An unbound one has no owner.
                skip.add(new InsnNode(DUP));
                skip.add(new MethodInsnNode(
                        INVOKEINTERFACE, "org/jetbrains/kotlin/ir/symbols/IrSymbol", "isBound", "()Z", true));
                skip.add(new JumpInsnNode(IFNE, cont));
                skip.add(new VarInsnNode(ASTORE, 12));
                skip.add(new VarInsnNode(ALOAD, 0));
                skip.add(new FieldInsnNode(
                        GETFIELD,
                        COLLECT_OWNER,
                        "this$0",
                        "Lorg/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver;"));
                skip.add(new VarInsnNode(ALOAD, 0));
                skip.add(new FieldInsnNode(
                        GETFIELD,
                        COLLECT_OWNER,
                        "$this_collectOverrides",
                        "Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"));
                skip.add(new VarInsnNode(ALOAD, 12));
                skip.add(new MethodInsnNode(
                        INVOKESTATIC,
                        RESOLVER_OWNER,
                        "skipUnbound",
                        "(Lorg/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver;"
                                + "Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"
                                + "Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                        false));
                skip.add(new InsnNode(POP));
                skip.add(new InsnNode(POP));
                skip.add(new FieldInsnNode(GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;"));
                skip.add(new InsnNode(ARETURN));
                skip.add(cont);
                method.instructions.insertBefore(insn, skip);
                ownerPatched = true;
                break;
            }
            if (!ownerPatched) {
                throw new IllegalStateException("collectOverrides getOwner not found");
            }
        }
        if (!patched) {
            throw new IllegalStateException("collectOverrides message not found");
        }
        ClassWriter writer = framesWriterWithStdlib();
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Const lowering calls getOwner on every call. JDK methods that 2.0.21 never
     * bound have no owner and are not const. Transform their children only.
     */
    private static byte[] patchConstTransformer(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        String desc = "(Lorg/jetbrains/kotlin/ir/expressions/IrCall;"
                + "Lorg/jetbrains/kotlin/ir/interpreter/transformer/IrConstTransformer$Data;)"
                + "Lorg/jetbrains/kotlin/ir/IrElement;";
        for (MethodNode method : node.methods) {
            if (!"visitCall".equals(method.name) || !desc.equals(method.desc)) {
                continue;
            }
            AbstractInsnNode children = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode) || !"transformChildren".equals(((MethodInsnNode) insn).name)) {
                    continue;
                }
                AbstractInsnNode aload2 = previousReal(insn);
                if (!(aload2 instanceof VarInsnNode)
                        || aload2.getOpcode() != ALOAD
                        || ((VarInsnNode) aload2).var != 2) {
                    continue;
                }
                AbstractInsnNode checkcast = previousReal(aload2);
                AbstractInsnNode aload0 = checkcast == null ? null : previousReal(checkcast);
                AbstractInsnNode aload1 = aload0 == null ? null : previousReal(aload0);
                if (!(aload1 instanceof VarInsnNode)
                        || aload1.getOpcode() != ALOAD
                        || ((VarInsnNode) aload1).var != 1) {
                    continue;
                }
                children = aload1;
            }
            if (children == null) {
                throw new IllegalStateException("const children transform not found");
            }
            LabelNode childrenLabel = new LabelNode();
            method.instructions.insertBefore(children, childrenLabel);
            AbstractInsnNode ownerCall = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode
                        && "getOwner".equals(((MethodInsnNode) insn).name)
                        && "org/jetbrains/kotlin/ir/symbols/IrSimpleFunctionSymbol".equals(
                                ((MethodInsnNode) insn).owner)) {
                    ownerCall = insn;
                    break;
                }
            }
            if (ownerCall == null) {
                throw new IllegalStateException("const getOwner not found");
            }
            LabelNode bound = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new InsnNode(DUP));
            guard.add(new MethodInsnNode(
                    INVOKEINTERFACE, "org/jetbrains/kotlin/ir/symbols/IrSymbol", "isBound", "()Z", true));
            guard.add(new JumpInsnNode(IFNE, bound));
            guard.add(new InsnNode(POP));
            guard.add(new JumpInsnNode(GOTO, childrenLabel));
            guard.add(bound);
            method.instructions.insertBefore(ownerCall, guard);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException("const visitCall not found");
        }
        ClassWriter writer = framesWriterWithStdlib();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Name the member whose override list contains the dangling symbol. */
    private static void addNoteCollect(ClassNode node) {
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "collectPrints", "I", null, null));
        MethodNode method = new MethodNode(
                ACC_PUBLIC | ACC_STATIC,
                "noteCollect",
                "(Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"
                        + "Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                null,
                null);
        InsnList in = method.instructions;
        LabelNode skip = new LabelNode();
        LabelNode unnamedParent = new LabelNode();
        LabelNode storeParent = new LabelNode();
        LabelNode notFake = new LabelNode();
        LabelNode sigNull = new LabelNode();
        LabelNode sigReady = new LabelNode();
        in.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "collectPrints", "I"));
        in.add(new IntInsnNode(BIPUSH, 40));
        in.add(new JumpInsnNode(IF_ICMPGE, skip));
        in.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "collectPrints", "I"));
        in.add(new InsnNode(ICONST_1));
        in.add(new InsnNode(IADD));
        in.add(new FieldInsnNode(PUTSTATIC, RESOLVER_OWNER, "collectPrints", "I"));
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName"));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName",
                "getName",
                "()Lorg/jetbrains/kotlin/name/Name;",
                true));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "org/jetbrains/kotlin/name/Name", "asString", "()Ljava/lang/String;", false));
        in.add(new VarInsnNode(ASTORE, 2));
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrDeclaration"));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrDeclaration",
                "getParent",
                "()Lorg/jetbrains/kotlin/ir/declarations/IrDeclarationParent;",
                true));
        in.add(new InsnNode(DUP));
        in.add(new TypeInsnNode(INSTANCEOF, "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName"));
        in.add(new JumpInsnNode(IFEQ, unnamedParent));
        in.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName"));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName",
                "getName",
                "()Lorg/jetbrains/kotlin/name/Name;",
                true));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "org/jetbrains/kotlin/name/Name", "asString", "()Ljava/lang/String;", false));
        in.add(new JumpInsnNode(GOTO, storeParent));
        in.add(unnamedParent);
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false));
        in.add(storeParent);
        in.add(new VarInsnNode(ASTORE, 3));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new TypeInsnNode(INSTANCEOF, "org/jetbrains/kotlin/ir/symbols/impl/IrFakeOverrideSymbolBase"));
        in.add(new JumpInsnNode(IFEQ, notFake));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/symbols/impl/IrFakeOverrideSymbolBase"));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL,
                "org/jetbrains/kotlin/ir/symbols/impl/IrFakeOverrideSymbolBase",
                "getSignature",
                "()Lorg/jetbrains/kotlin/ir/util/IdSignature;",
                false));
        in.add(new InsnNode(DUP));
        in.add(new JumpInsnNode(IFNULL, sigNull));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "org/jetbrains/kotlin/ir/util/IdSignature", "render", "()Ljava/lang/String;", false));
        in.add(new JumpInsnNode(GOTO, sigReady));
        in.add(sigNull);
        in.add(new InsnNode(POP));
        in.add(new LdcInsnNode("null"));
        in.add(new JumpInsnNode(GOTO, sigReady));
        in.add(notFake);
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false));
        in.add(sigReady);
        in.add(new VarInsnNode(ASTORE, 4));
        in.add(new FieldInsnNode(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
        in.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
        in.add(new InsnNode(DUP));
        in.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        in.add(new LdcInsnNode("COLLECT "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" parent "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 3));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" sig "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 4));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        in.add(skip);
        in.add(new InsnNode(RETURN));
        node.methods.add(method);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    /**
     * Drop override-list entries that are unbound or that point at the function itself.
     * Retargeting those symbols onto the member fixed calls and made the member override itself.
     */
    private static void filterOverrideAdds(MethodNode method, int[] functionLocals) {
        int index = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)
                    || !"getReferencedSimpleFunction".equals(((MethodInsnNode) insn).name)) {
                continue;
            }
            AbstractInsnNode load = nextReal(insn);
            AbstractInsnNode swap = load == null ? null : nextReal(load);
            AbstractInsnNode add = swap == null ? null : nextReal(swap);
            AbstractInsnNode pop = add == null ? null : nextReal(add);
            if (!(add instanceof MethodInsnNode)
                    || !"add".equals(((MethodInsnNode) add).name)
                    || load.getOpcode() != ALOAD
                    || swap.getOpcode() != SWAP
                    || pop == null
                    || pop.getOpcode() != POP) {
                continue;
            }
            if (index >= functionLocals.length) {
                throw new IllegalStateException("extra override add in " + method.name);
            }
            LabelNode keep = new LabelNode();
            LabelNode skip = new LabelNode();
            InsnList filter = new InsnList();
            filter.add(new VarInsnNode(ALOAD, functionLocals[index]));
            filter.add(new InsnNode(SWAP));
            filter.add(new MethodInsnNode(
                    INVOKESTATIC,
                    "StdlibDiag",
                    "keepOverride",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    false));
            filter.add(new InsnNode(DUP));
            filter.add(new JumpInsnNode(IFNONNULL, keep));
            filter.add(new InsnNode(POP));
            filter.add(new JumpInsnNode(GOTO, skip));
            filter.add(keep);
            method.instructions.insert(insn, filter);
            method.instructions.insert(pop, skip);
            index++;
            insn = skip;
        }
        if (index != functionLocals.length) {
            throw new IllegalStateException(method.name + " override adds " + index);
        }
    }

    /** Cache a dangling override under its containing class and do not walk it. */
    private static void addSkipUnbound(ClassNode node) {
        MethodNode method = new MethodNode(
                ACC_PUBLIC | ACC_STATIC,
                "skipUnbound",
                "(Lorg/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver;"
                        + "Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"
                        + "Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                null,
                null);
        InsnList in = method.instructions;
        LabelNode notClass = new LabelNode();
        LabelNode end = new LabelNode();
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new MethodInsnNode(
                INVOKESTATIC,
                RESOLVER_OWNER,
                "noteCollect",
                "(Lorg/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration;"
                        + "Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                false));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrDeclaration"));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrDeclaration",
                "getParent",
                "()Lorg/jetbrains/kotlin/ir/declarations/IrDeclarationParent;",
                true));
        in.add(new InsnNode(DUP));
        in.add(new TypeInsnNode(INSTANCEOF, "org/jetbrains/kotlin/ir/declarations/IrClass"));
        in.add(new JumpInsnNode(IFEQ, notClass));
        in.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrClass"));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL,
                "org/jetbrains/kotlin/ir/declarations/IrClass",
                "getSymbol",
                "()Lorg/jetbrains/kotlin/ir/symbols/IrClassSymbol;",
                false));
        in.add(new VarInsnNode(ASTORE, 3));
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new MethodInsnNode(
                INVOKESTATIC,
                RESOLVER_OWNER,
                "access$getCachedFakeOverrides$p",
                "(Lorg/jetbrains/kotlin/backend/common/actualizer/SpecialFakeOverrideSymbolsResolver;)Ljava/util/Map;",
                false));
        in.add(new VarInsnNode(ALOAD, 3));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new MethodInsnNode(
                INVOKESTATIC,
                "kotlin/TuplesKt",
                "to",
                "(Ljava/lang/Object;Ljava/lang/Object;)Lkotlin/Pair;",
                false));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration",
                "getSymbol",
                "()Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;",
                true));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "java/util/Map",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        in.add(new InsnNode(POP));
        in.add(new JumpInsnNode(GOTO, end));
        in.add(notClass);
        in.add(new InsnNode(POP));
        in.add(end);
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrOverridableDeclaration",
                "getSymbol",
                "()Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;",
                true));
        in.add(new MethodInsnNode(
                INVOKESTATIC,
                RESOLVER_OWNER,
                "redirectPut",
                "(Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                false));
        in.add(new InsnNode(RETURN));
        node.methods.add(method);
    }

    /** Remember unbound symbols so later calls resolve to the real member. */
    private static void addRedirectMap(ClassNode node) {
        node.fields.add(new FieldNode(
                ACC_PRIVATE | ACC_STATIC, "unboundRedirect", "Ljava/util/Map;", null, null));
        MethodNode put = new MethodNode(
                ACC_PUBLIC | ACC_STATIC,
                "redirectPut",
                "(Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)V",
                null,
                null);
        InsnList pin = put.instructions;
        LabelNode ready = new LabelNode();
        pin.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "unboundRedirect", "Ljava/util/Map;"));
        pin.add(new JumpInsnNode(IFNONNULL, ready));
        pin.add(new TypeInsnNode(NEW, "java/util/IdentityHashMap"));
        pin.add(new InsnNode(DUP));
        pin.add(new MethodInsnNode(
                INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false));
        pin.add(new FieldInsnNode(PUTSTATIC, RESOLVER_OWNER, "unboundRedirect", "Ljava/util/Map;"));
        pin.add(ready);
        pin.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "unboundRedirect", "Ljava/util/Map;"));
        pin.add(new VarInsnNode(ALOAD, 0));
        pin.add(new VarInsnNode(ALOAD, 1));
        pin.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "java/util/Map",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        pin.add(new InsnNode(POP));
        pin.add(new InsnNode(RETURN));
        node.methods.add(put);
        MethodNode get = new MethodNode(
                ACC_PUBLIC | ACC_STATIC,
                "redirectGet",
                "(Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;",
                null,
                null);
        InsnList gin = get.instructions;
        LabelNode miss = new LabelNode();
        gin.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "unboundRedirect", "Ljava/util/Map;"));
        gin.add(new JumpInsnNode(IFNULL, miss));
        gin.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "unboundRedirect", "Ljava/util/Map;"));
        gin.add(new VarInsnNode(ALOAD, 0));
        gin.add(new MethodInsnNode(
                INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        gin.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/symbols/IrSymbol"));
        gin.add(new InsnNode(ARETURN));
        gin.add(miss);
        gin.add(new InsnNode(ACONST_NULL));
        gin.add(new InsnNode(ARETURN));
        node.methods.add(get);
    }

    private static void insertRedirectLookup(MethodNode method) {
        AbstractInsnNode store = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode && insn.getOpcode() == ASTORE && ((VarInsnNode) insn).var == 3) {
                store = insn;
                break;
            }
        }
        if (store == null) {
            throw new IllegalStateException("referenced symbol store not found");
        }
        LabelNode miss = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(ALOAD, 3));
        lookup.add(new MethodInsnNode(
                INVOKESTATIC,
                RESOLVER_OWNER,
                "redirectGet",
                "(Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;)Lorg/jetbrains/kotlin/ir/symbols/IrSymbol;",
                false));
        lookup.add(new InsnNode(DUP));
        lookup.add(new JumpInsnNode(IFNULL, miss));
        lookup.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/symbols/IrSimpleFunctionSymbol"));
        lookup.add(new InsnNode(ARETURN));
        lookup.add(miss);
        lookup.add(new InsnNode(POP));
        method.instructions.insert(store, lookup);
    }

    private static void addNoteIr(ClassNode node) {
        node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "lastIrName", "Ljava/lang/String;", null, null));
        MethodNode method = new MethodNode(
                ACC_PUBLIC | ACC_STATIC,
                "noteIr",
                "(Lorg/jetbrains/kotlin/ir/declarations/IrSimpleFunction;)V",
                null,
                null);
        InsnList in = method.instructions;
        LabelNode unnamedParent = new LabelNode();
        LabelNode storeParent = new LabelNode();
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName",
                "getName",
                "()Lorg/jetbrains/kotlin/name/Name;",
                true));
        in.add(new MethodInsnNode(
                INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false));
        in.add(new VarInsnNode(ASTORE, 1));
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrDeclaration",
                "getParent",
                "()Lorg/jetbrains/kotlin/ir/declarations/IrDeclarationParent;",
                true));
        in.add(new InsnNode(DUP));
        in.add(new TypeInsnNode(INSTANCEOF, "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName"));
        in.add(new JumpInsnNode(IFEQ, unnamedParent));
        in.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName"));
        in.add(new MethodInsnNode(
                INVOKEINTERFACE,
                "org/jetbrains/kotlin/ir/declarations/IrDeclarationWithName",
                "getName",
                "()Lorg/jetbrains/kotlin/name/Name;",
                true));
        in.add(new JumpInsnNode(GOTO, storeParent));
        in.add(unnamedParent);
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false));
        in.add(storeParent);
        in.add(new MethodInsnNode(
                INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false));
        in.add(new VarInsnNode(ASTORE, 2));
        in.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
        in.add(new InsnNode(DUP));
        in.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" parent "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        in.add(new FieldInsnNode(PUTSTATIC, RESOLVER_OWNER, "lastIrName", "Ljava/lang/String;"));
        in.add(new InsnNode(RETURN));
        node.methods.add(method);
    }

    /** Remember the IR function whose override list is about to be resolved. */
    private static byte[] patchPipeline(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        int found = 0;
        int[] locals = {21, 8, 8};
        for (MethodNode method : node.methods) {
            if (!"resolveOverridenSymbolsInLazyClass".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!"getReferencedSimpleFunction".equals(call.name) || !RESOLVER_OWNER.equals(call.owner)) {
                    continue;
                }
                if (found >= locals.length) {
                    throw new IllegalStateException("unexpected getReferencedSimpleFunction site");
                }
                AbstractInsnNode symbolLoad = call.getPrevious();
                AbstractInsnNode resolverLoad = symbolLoad == null ? null : symbolLoad.getPrevious();
                if (resolverLoad == null) {
                    throw new IllegalStateException("call prefix missing");
                }
                InsnList note = new InsnList();
                note.add(new VarInsnNode(ALOAD, locals[found]));
                note.add(new MethodInsnNode(
                        INVOKESTATIC,
                        RESOLVER_OWNER,
                        "noteIr",
                        "(Lorg/jetbrains/kotlin/ir/declarations/IrSimpleFunction;)V",
                        false));
                method.instructions.insertBefore(resolverLoad, note);
                found++;
            }
        }
        if (found != locals.length) {
            throw new IllegalStateException("override call sites " + found);
        }
        if (!indexOverridesBeforeVisitor(node)) {
            throw new IllegalStateException("fake override visitor call not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * A member whose override list points at itself makes inline-class replacement recurse.
     * Skip that parent. Other parents are still replaced.
     */
    private static byte[] patchInlineReplacements(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"replaceOverriddenSymbolsImpl$lambda$6".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode) || !"getOwner".equals(((MethodInsnNode) insn).name)) {
                    continue;
                }
                AbstractInsnNode cast = nextReal(insn);
                AbstractInsnNode call = cast == null ? null : nextReal(cast);
                if (!(cast instanceof TypeInsnNode)
                        || cast.getOpcode() != CHECKCAST
                        || !(call instanceof MethodInsnNode)
                        || !"computeOverrideReplacement".equals(((MethodInsnNode) call).name)) {
                    continue;
                }
                AbstractInsnNode add = call;
                while (add != null
                        && !(add instanceof MethodInsnNode && "add".equals(((MethodInsnNode) add).name))) {
                    add = nextReal(add);
                }
                AbstractInsnNode pop = add == null ? null : nextReal(add);
                if (pop == null || pop.getOpcode() != POP) {
                    throw new IllegalStateException("inline replacement add not found");
                }
                LabelNode keep = new LabelNode();
                LabelNode skip = new LabelNode();
                InsnList filter = new InsnList();
                filter.add(new InsnNode(DUP));
                filter.add(new VarInsnNode(ALOAD, 1));
                filter.add(new JumpInsnNode(IF_ACMPNE, keep));
                filter.add(new InsnNode(POP));
                filter.add(new InsnNode(POP));
                filter.add(new JumpInsnNode(GOTO, skip));
                filter.add(keep);
                method.instructions.insert(insn, filter);
                method.instructions.insert(pop, skip);
                patched = true;
                break;
            }
        }
        if (!patched) {
            throw new IllegalStateException("inline override lambda not patched");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Treat kotlin.LibraryKt as package kotlin for Any?.toString and String?.plus. */
    private static byte[] patchFlatten(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        int replaced = 0;
        for (MethodNode method : node.methods) {
            if (!"isNullableToString".equals(method.name) && !"isStringPlusCall".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() == INVOKEVIRTUAL
                        && "org/jetbrains/kotlin/name/FqName".equals(call.owner)
                        && "parent".equals(call.name)
                        && "()Lorg/jetbrains/kotlin/name/FqName;".equals(call.desc)) {
                    method.instructions.set(insn, new MethodInsnNode(
                            INVOKESTATIC,
                            "StdlibDiag",
                            "builtinParent",
                            "(Ljava/lang/Object;)Ljava/lang/Object;",
                            false));
                    replaced++;
                }
            }
        }
        if (replaced != 2) {
            throw new IllegalStateException("facade parent sites " + replaced);
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Do not abort stdlib codegen on the mapped Map keySet/values/entrySet pair. */
    private static byte[] patchClashDetector(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"reportSignatureConflict".equals(method.name)
                    || !method.desc.startsWith("(Lorg/jetbrains/kotlin/resolve/jvm/diagnostics/RawSignature;")) {
                continue;
            }
            AbstractInsnNode insertAt = null;
            int checks = 0;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode
                        && "checkNotNullParameter".equals(((MethodInsnNode) insn).name)) {
                    checks++;
                    if (checks == 3) {
                        insertAt = insn.getNext();
                        break;
                    }
                }
            }
            if (insertAt == null) {
                throw new IllegalStateException("clash report checks not found");
            }
            LabelNode go = new LabelNode();
            InsnList skip = new InsnList();
            skip.add(new VarInsnNode(ALOAD, 1));
            skip.add(new MethodInsnNode(
                    INVOKESTATIC,
                    "StdlibDiag",
                    "ignoreCollectionClash",
                    "(Ljava/lang/Object;)Z",
                    false));
            skip.add(new JumpInsnNode(IFEQ, go));
            skip.add(new InsnNode(RETURN));
            skip.add(go);
            method.instructions.insertBefore(insertAt, skip);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException("clash report not patched");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** An empty fake-override chain still has a usable JVM descriptor. */
    private static byte[] patchFindSuper(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"findSuperDeclaration".equals(method.name)) {
                continue;
            }
            AbstractInsnNode message = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode
                        && String.valueOf(((LdcInsnNode) insn).cst).startsWith(
                                "Fake override should have at least one overridden descriptor")) {
                    message = insn;
                    break;
                }
            }
            if (message == null) {
                throw new IllegalStateException("fake override message not found");
            }
            AbstractInsnNode start = message;
            while (start != null
                    && !(start.getOpcode() == NEW
                            && start instanceof TypeInsnNode
                            && "java/lang/IllegalStateException".equals(((TypeInsnNode) start).desc))) {
                start = start.getPrevious();
            }
            AbstractInsnNode end = message;
            while (end != null && end.getOpcode() != ATHROW) {
                end = end.getNext();
            }
            if (start == null || end == null) {
                throw new IllegalStateException("fake override throw not found");
            }
            AbstractInsnNode after = end.getNext();
            AbstractInsnNode cursor = start;
            while (cursor != end) {
                AbstractInsnNode next = cursor.getNext();
                method.instructions.remove(cursor);
                cursor = next;
            }
            method.instructions.remove(end);
            InsnList ret = new InsnList();
            ret.add(new VarInsnNode(ALOAD, 3));
            ret.add(new MethodInsnNode(
                    INVOKESTATIC, "StdlibDiag", "noteEmptyOverride", "(Ljava/lang/Object;)V", false));
            ret.add(new VarInsnNode(ALOAD, 3));
            ret.add(new InsnNode(ARETURN));
            method.instructions.insertBefore(after, ret);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException("findSuperDeclaration not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Do not emit class files for jvm builtin sources. */
    private static byte[] patchFileCodegen(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"lower".equals(method.name)
                    || !"(Lorg/jetbrains/kotlin/ir/declarations/IrFile;)V".equals(method.desc)) {
                continue;
            }
            AbstractInsnNode check = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode
                        && "checkNotNullParameter".equals(((MethodInsnNode) insn).name)) {
                    check = insn;
                    break;
                }
            }
            if (check == null) {
                throw new IllegalStateException("file codegen null check not found");
            }
            LabelNode go = new LabelNode();
            InsnList skip = new InsnList();
            skip.add(new VarInsnNode(ALOAD, 1));
            skip.add(new MethodInsnNode(
                    INVOKESTATIC, "StdlibDiag", "suppressBytecode", "(Ljava/lang/Object;)Z", false));
            skip.add(new JumpInsnNode(IFEQ, go));
            skip.add(new InsnNode(RETURN));
            skip.add(go);
            method.instructions.insert(check, skip);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException("file codegen lower not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Same self-override filter for classes visited directly, not only lazy classes. */
    private static byte[] patchResolverVisitor(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean filtered = false;
        for (MethodNode method : node.methods) {
            if (!"visitSimpleFunction".equals(method.name)
                    || !"(Lorg/jetbrains/kotlin/ir/declarations/IrSimpleFunction;)V".equals(method.desc)) {
                continue;
            }
            filterOverrideAdds(method, new int[] {1});
            filtered = true;
        }
        if (!filtered) {
            throw new IllegalStateException("visitSimpleFunction not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Print Array declarations before the size-property lookup aborts stdlib lowering. */
    private static byte[] patchMappedEnum(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) {
                continue;
            }
            AbstractInsnNode sizeName = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode && "size".equals(((LdcInsnNode) insn).cst)) {
                    sizeName = insn;
                    break;
                }
            }
            if (sizeName == null) {
                throw new IllegalStateException("array size name not found");
            }
            AbstractInsnNode throwSite = sizeName.getNext();
            while (throwSite != null) {
                if (throwSite.getOpcode() == NEW
                        && throwSite instanceof TypeInsnNode
                        && "java/util/NoSuchElementException".equals(((TypeInsnNode) throwSite).desc)) {
                    break;
                }
                throwSite = throwSite.getNext();
            }
            if (throwSite == null) {
                throw new IllegalStateException("array size throw not found");
            }
            LabelNode missing = new LabelNode();
            InsnList note = new InsnList();
            note.add(new VarInsnNode(ALOAD, 0));
            note.add(new MethodInsnNode(
                    INVOKEVIRTUAL,
                    "org/jetbrains/kotlin/backend/jvm/lower/MappedEnumWhenLowering",
                    "getContext",
                    "()Lorg/jetbrains/kotlin/backend/jvm/JvmBackendContext;",
                    false));
            note.add(new MethodInsnNode(
                    INVOKEVIRTUAL,
                    "org/jetbrains/kotlin/backend/jvm/JvmBackendContext",
                    "getIrBuiltIns",
                    "()Lorg/jetbrains/kotlin/ir/IrBuiltIns;",
                    false));
            note.add(new MethodInsnNode(
                    INVOKEVIRTUAL,
                    "org/jetbrains/kotlin/ir/IrBuiltIns",
                    "getArrayClass",
                    "()Lorg/jetbrains/kotlin/ir/symbols/IrClassSymbol;",
                    false));
            note.add(new MethodInsnNode(
                    INVOKESTATIC, "StdlibDiag", "arraySizeGetter", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            note.add(new InsnNode(DUP));
            note.add(new JumpInsnNode(IFNULL, missing));
            note.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrSimpleFunction"));
            note.add(new VarInsnNode(ALOAD, 0));
            note.add(new InsnNode(SWAP));
            note.add(new FieldInsnNode(
                    PUTFIELD,
                    "org/jetbrains/kotlin/backend/jvm/lower/MappedEnumWhenLowering",
                    "refArraySize",
                    "Lorg/jetbrains/kotlin/ir/declarations/IrSimpleFunction;"));
            note.add(new InsnNode(RETURN));
            note.add(missing);
            note.add(new InsnNode(POP));
            method.instructions.insertBefore(throwSite, note);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException("mapped enum constructor not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Fill the override cache before calls are remapped, so unbound symbols can be replaced. */
    private static boolean indexOverridesBeforeVisitor(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!"resolveFakeOverrideSymbols".equals(call.name)) {
                    continue;
                }
                AbstractInsnNode resolver = previousReal(call);
                AbstractInsnNode result = previousReal(resolver);
                AbstractInsnNode receiver = previousReal(result);
                if (!(receiver instanceof VarInsnNode)
                        || receiver.getOpcode() != ALOAD
                        || ((VarInsnNode) receiver).var != 0) {
                    throw new IllegalStateException("resolveFakeOverrideSymbols prefix is not aload_0");
                }
                InsnList early = new InsnList();
                early.add(new VarInsnNode(ALOAD, 8));
                early.add(new VarInsnNode(ALOAD, 1));
                early.add(new MethodInsnNode(
                        INVOKEVIRTUAL,
                        "org/jetbrains/kotlin/fir/pipeline/Fir2IrPipeline$Fir2IrConversionResult",
                        "getMainIrFragment",
                        "()Lorg/jetbrains/kotlin/ir/declarations/impl/IrModuleFragmentImpl;",
                        false));
                early.add(new TypeInsnNode(CHECKCAST, "org/jetbrains/kotlin/ir/declarations/IrModuleFragment"));
                early.add(new MethodInsnNode(
                        INVOKEVIRTUAL,
                        RESOLVER_OWNER,
                        "cacheFakeOverridesOfAllClasses",
                        "(Lorg/jetbrains/kotlin/ir/declarations/IrModuleFragment;)V",
                        false));
                method.instructions.insertBefore(receiver, early);
                return true;
            }
        }
        return false;
    }

    private static InsnList cappedFosig() {
        InsnList in = new InsnList();
        LabelNode skip = new LabelNode();
        in.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "fosigPrints", "I"));
        in.add(new IntInsnNode(BIPUSH, 40));
        in.add(new JumpInsnNode(IF_ICMPGE, skip));
        in.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "fosigPrints", "I"));
        in.add(new InsnNode(ICONST_1));
        in.add(new InsnNode(IADD));
        in.add(new FieldInsnNode(PUTSTATIC, RESOLVER_OWNER, "fosigPrints", "I"));
        in.add(cappedFosigBody());
        in.add(skip);
        return in;
    }

    private static InsnList cappedFosigBody() {
        return fakeSignaturePrint();
    }

    private static InsnList fakeSignaturePrint() {
        InsnList in = new InsnList();
        appendConst(in, "FOSIG ");
        in.add(new FieldInsnNode(GETSTATIC, RESOLVER_OWNER, "lastIrName", "Ljava/lang/String;"));
        appendObject(in);
        finishPrint(in);
        return in;
    }

    private static void appendConst(InsnList in, String text) {
        if (in.getFirst() == null) {
            in.add(new FieldInsnNode(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
            in.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
            in.add(new InsnNode(DUP));
            in.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        }
        in.add(new LdcInsnNode(text));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
    }

    private static void appendObject(InsnList in) {
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
    }

    private static void finishPrint(InsnList in) {
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
    }

    /**
     * Keep the 2.0.21 modality map. The stdlib mismatches are an OPEN or FINAL expect
     * against an ABSTRACT actual, so those pairs are also accepted. Each mismatch prints
     * the expect and actual symbols. The patched jar is a local build tool only.
     */
    private static byte[] patchModality(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "modPairPrints", "I", null, null));
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "modSymPrints", "I", null, null));
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "modWidenHit", "Z", null, null));
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            if (!"areCompatibleModalities".equals(method.name) || !MODALITY_DESC.equals(method.desc)) {
                continue;
            }
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) {
                method.localVariables.clear();
            }
            method.instructions.add(modalityBody());
            replaced = true;
            break;
        }
        if (!replaced) {
            throw new IllegalStateException("method not found areCompatibleModalities");
        }
        if (!insertSymbolTrace(node)) {
            throw new IllegalStateException("modality call site not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /** On the modality-failure edge, print the expect and actual callable symbols. */
    private static boolean insertSymbolTrace(ClassNode node) {
        String owner = "org/jetbrains/kotlin/resolve/calls/mpp/AbstractExpectActualChecker";
        for (MethodNode method : node.methods) {
            if (!"getCallablesCompatibility".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!"areCompatibleModalities".equals(call.name) || !owner.equals(call.owner)) {
                    continue;
                }
                AbstractInsnNode next = insn.getNext();
                if (!(next instanceof JumpInsnNode) || next.getOpcode() != IFNE) {
                    continue;
                }
                method.instructions.insert(insn, symbolTrace());
                return true;
            }
        }
        return false;
    }

    /** Precondition: the boolean result of areCompatibleModalities is already on the stack. */
    private static InsnList symbolTrace() {
        InsnList in = new InsnList();
        LabelNode skip = new LabelNode();
        String owner = "org/jetbrains/kotlin/resolve/calls/mpp/AbstractExpectActualChecker";
        in.add(new FieldInsnNode(GETSTATIC, owner, "modWidenHit", "Z"));
        in.add(new JumpInsnNode(IFEQ, skip));
        in.add(new InsnNode(ICONST_0));
        in.add(new FieldInsnNode(PUTSTATIC, owner, "modWidenHit", "Z"));
        in.add(new FieldInsnNode(GETSTATIC, owner, "modSymPrints", "I"));
        in.add(new IntInsnNode(BIPUSH, 20));
        in.add(new JumpInsnNode(IF_ICMPGE, skip));
        in.add(new FieldInsnNode(GETSTATIC, owner, "modSymPrints", "I"));
        in.add(new InsnNode(ICONST_1));
        in.add(new InsnNode(IADD));
        in.add(new FieldInsnNode(PUTSTATIC, owner, "modSymPrints", "I"));
        in.add(new FieldInsnNode(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
        in.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
        in.add(new InsnNode(DUP));
        in.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        in.add(new LdcInsnNode("MODSYM "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" || "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 3));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 3));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        in.add(skip);
        return in;
    }

    private static InsnList modalityBody() {
        InsnList in = new InsnList();
        LabelNode retTrue = new LabelNode();
        LabelNode retFalse = new LabelNode();
        LabelNode skipPrint = new LabelNode();
        LabelNode widen = new LabelNode();
        String owner = "org/jetbrains/kotlin/resolve/calls/mpp/AbstractExpectActualChecker";
        String modality = "org/jetbrains/kotlin/descriptors/Modality";
        String modalityType = "L" + modality + ";";
        String effective = "(" + modalityType + modalityType + ")" + modalityType;

        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new VarInsnNode(ALOAD, 3));
        in.add(new MethodInsnNode(INVOKESPECIAL, owner, "effectiveModality", effective, false));
        in.add(new VarInsnNode(ASTORE, 5));
        in.add(new VarInsnNode(ALOAD, 0));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new VarInsnNode(ALOAD, 4));
        in.add(new MethodInsnNode(INVOKESPECIAL, owner, "effectiveModality", effective, false));
        in.add(new VarInsnNode(ASTORE, 6));

        in.add(new FieldInsnNode(GETSTATIC, owner, "compatibleModalityMap", "Ljava/util/EnumMap;"));
        in.add(new TypeInsnNode(CHECKCAST, "java/util/Map"));
        in.add(new VarInsnNode(ALOAD, 5));
        in.add(new MethodInsnNode(
                INVOKESTATIC,
                "kotlin/collections/MapsKt",
                "getValue",
                "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        in.add(new TypeInsnNode(CHECKCAST, "java/util/EnumSet"));
        in.add(new VarInsnNode(ALOAD, 6));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/util/EnumSet", "contains", "(Ljava/lang/Object;)Z", false));
        in.add(new JumpInsnNode(IFNE, retTrue));

        in.add(new FieldInsnNode(GETSTATIC, owner, "modPairPrints", "I"));
        in.add(new IntInsnNode(BIPUSH, 80));
        in.add(new JumpInsnNode(IF_ICMPGE, skipPrint));
        in.add(new FieldInsnNode(GETSTATIC, owner, "modPairPrints", "I"));
        in.add(new InsnNode(ICONST_1));
        in.add(new InsnNode(IADD));
        in.add(new FieldInsnNode(PUTSTATIC, owner, "modPairPrints", "I"));
        in.add(new FieldInsnNode(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
        in.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
        in.add(new InsnNode(DUP));
        in.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        in.add(new LdcInsnNode("MODPAIR member "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 1));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" vs "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 2));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" class "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 3));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" vs "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 4));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" effective "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 5));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new LdcInsnNode(" vs "));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        in.add(new VarInsnNode(ALOAD, 6));
        in.add(new MethodInsnNode(
                INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        in.add(new MethodInsnNode(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        in.add(skipPrint);

        in.add(new VarInsnNode(ALOAD, 6));
        in.add(new FieldInsnNode(GETSTATIC, modality, "ABSTRACT", modalityType));
        in.add(new JumpInsnNode(IF_ACMPNE, retFalse));
        in.add(new VarInsnNode(ALOAD, 5));
        in.add(new FieldInsnNode(GETSTATIC, modality, "OPEN", modalityType));
        in.add(new JumpInsnNode(IF_ACMPEQ, widen));
        in.add(new VarInsnNode(ALOAD, 5));
        in.add(new FieldInsnNode(GETSTATIC, modality, "FINAL", modalityType));
        in.add(new JumpInsnNode(IF_ACMPNE, retFalse));
        in.add(widen);
        in.add(new InsnNode(ICONST_1));
        in.add(new FieldInsnNode(PUTSTATIC, owner, "modWidenHit", "Z"));
        in.add(new JumpInsnNode(GOTO, retTrue));
        in.add(retFalse);
        in.add(new InsnNode(ICONST_0));
        in.add(new InsnNode(IRETURN));
        in.add(retTrue);
        in.add(new InsnNode(ICONST_1));
        in.add(new InsnNode(IRETURN));
        return in;
    }

    /** Replace a boolean method with a constant true. Used for a local build-tool compiler only. */
    private static byte[] returnTrue(String name, String desc, byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) {
                continue;
            }
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) {
                method.localVariables.clear();
            }
            method.instructions.add(new InsnNode(ICONST_1));
            method.instructions.add(new InsnNode(IRETURN));
            replaced = true;
            break;
        }
        if (!replaced) {
            throw new IllegalStateException("method not found " + name);
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * sinceVersion &gt; apiVersion takes the hidden branch. Drop that jump so a 2.4
     * declaration stays visible to a compiler whose highest API version is 2.2.
     */
    private static byte[] dropFirstIfgt(String name, String desc, byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            if (!name.equals(method.name) || !desc.equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof JumpInsnNode && insn.getOpcode() == IFGT) {
                    InsnList nops = new InsnList();
                    nops.add(new InsnNode(POP));
                    nops.add(new InsnNode(NOP));
                    nops.add(new InsnNode(NOP));
                    method.instructions.insertBefore(insn, nops);
                    method.instructions.remove(insn);
                    replaced = true;
                    break;
                }
            }
        }
        if (!replaced) {
            throw new IllegalStateException("ifgt not found in " + name);
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Turn the unresolved-status check into a return so stdlib fake overrides do not abort analysis. */
    private static byte[] patchStatus(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            if (!"checkStatusIsResolved".equals(method.name) || !STATUS_DESC.equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof JumpInsnNode && insn.getOpcode() == IFNE) {
                    ((JumpInsnNode) insn).setOpcode(GOTO);
                    replaced = true;
                    break;
                }
            }
        }
        if (!replaced) {
            throw new IllegalStateException("status check jump not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] patchScope(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            if (!SCOPE_METHOD.equals(method.name) || !SCOPE_DESC.equals(method.desc)) {
                continue;
            }
            AbstractInsnNode insn = method.instructions.getFirst();
            while (insn != null) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof TypeInsnNode
                        && insn.getOpcode() == NEW
                        && "java/util/NoSuchElementException".equals(((TypeInsnNode) insn).desc)) {
                    AbstractInsnNode dup = insn.getNext();
                    AbstractInsnNode ldc = dup == null ? null : dup.getNext();
                    AbstractInsnNode init = ldc == null ? null : ldc.getNext();
                    AbstractInsnNode athrow = init == null ? null : init.getNext();
                    if (dup != null
                            && dup.getOpcode() == DUP
                            && ldc instanceof LdcInsnNode
                            && "Collection contains no element matching the predicate."
                                    .equals(((LdcInsnNode) ldc).cst)
                            && init instanceof MethodInsnNode
                            && athrow != null
                            && athrow.getOpcode() == ATHROW) {
                        InsnList fallback = new InsnList();
                        fallback.add(new VarInsnNode(ALOAD, 0));
                        fallback.add(new FieldInsnNode(
                                GETFIELD,
                                "org/jetbrains/kotlin/fir/scopes/impl/FirIntegerConstantOperatorScope",
                                "baseScope",
                                "Lorg/jetbrains/kotlin/fir/scopes/FirTypeScope;"));
                        fallback.add(new VarInsnNode(ALOAD, 1));
                        fallback.add(new VarInsnNode(ALOAD, 2));
                        fallback.add(new MethodInsnNode(
                                INVOKEVIRTUAL,
                                "org/jetbrains/kotlin/fir/scopes/FirTypeScope",
                                "processFunctionsByName",
                                "(Lorg/jetbrains/kotlin/name/Name;Lkotlin/jvm/functions/Function1;)V",
                                false));
                        fallback.add(new InsnNode(RETURN));
                        AbstractInsnNode ret = fallback.getLast();
                        method.instructions.insert(athrow, fallback);
                        method.instructions.remove(insn);
                        method.instructions.remove(dup);
                        method.instructions.remove(ldc);
                        method.instructions.remove(init);
                        method.instructions.remove(athrow);
                        replaced = true;
                        next = ret.getNext();
                    }
                }
                insn = next;
            }
        }
        if (!replaced) {
            throw new IllegalStateException("integer-constant throw site not found");
        }
        ClassWriter writer = framesWriter();
        node.accept(writer);
        return writer.toByteArray();
    }

    private static ClassWriter framesWriterWithStdlib() {
        try {
            URL compiler = Path.of("/usr/share/kotlin/lib/kotlin-compiler.jar").toUri().toURL();
            URL stdlib = Path.of("/usr/share/kotlin/lib/kotlin-stdlib.jar").toUri().toURL();
            URLClassLoader loader = new URLClassLoader(new URL[] {compiler, stdlib});
            return frameWriter(loader);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ClassWriter framesWriter() throws IOException {
        URL url = Path.of("/usr/share/kotlin/lib/kotlin-compiler.jar").toUri().toURL();
        URLClassLoader loader = new URLClassLoader(new URL[] {url});
        return frameWriter(loader);
    }

    private static ClassWriter frameWriter(URLClassLoader loader) {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                    Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                    if (c1.isAssignableFrom(c2)) {
                        return type1;
                    }
                    if (c2.isAssignableFrom(c1)) {
                        return type2;
                    }
                    if (c1.isInterface() || c2.isInterface()) {
                        return "java/lang/Object";
                    }
                    do {
                        c1 = c1.getSuperclass();
                    } while (c1 != null && !c1.isAssignableFrom(c2));
                    return c1 == null ? "java/lang/Object" : c1.getName().replace('.', '/');
                } catch (Throwable ignored) {
                    return "java/lang/Object";
                }
            }
        };
    }

    private static byte[] patch(byte[] data) throws IOException {
        ClassReader reader = new ClassReader(data);
        ClassWriter writer = framesWriter();
        ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!METHOD.equals(name) || !DESC.equals(descriptor)) {
                    return parent;
                }
                return new MethodVisitor(ASM9, parent) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitVarInsn(ALOAD, 2);
                        visitMethodInsn(
                                INVOKEVIRTUAL,
                                "org/jetbrains/kotlin/fir/symbols/impl/FirCallableSymbol",
                                "getSource",
                                "()Lorg/jetbrains/kotlin/KtSourceElement;",
                                false);
                        Label present = new Label();
                        visitJumpInsn(IFNONNULL, present);
                        visitInsn(RETURN);
                        visitLabel(present);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
}
