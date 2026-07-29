package me.darknet.dex.convert;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JvmLocalMaterializationCleanupTest {
    @Test
    void extendsPairedOuterRangesAcrossProvenCleanupTail() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/util/Map;Ljava/lang/String;)V", null, null);
        LabelNode bodyStart = new LabelNode();
        LabelNode cleanupStart = new LabelNode();
        LabelNode afterCleanup = new LabelNode();
        LabelNode failureHandler = new LabelNode();
        LabelNode finallyHandler = new LabelNode();
        method.instructions.add(bodyStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(cleanupStart);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, afterCleanup));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/Map", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/Map", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(afterCleanup);
        method.instructions.add(failureHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "android/util/Log", "w", "(Ljava/lang/String;)I", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(finallyHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/Map", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/Map", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(bodyStart, cleanupStart, failureHandler, null));
        method.tryCatchBlocks.add(new TryCatchBlockNode(bodyStart, cleanupStart, finallyHandler, null));

        assertEquals(2, JvmLocalMaterializationCleanup.extendPairedOuterRangesOverCleanup(method));
        assertEquals(afterCleanup, method.tryCatchBlocks.get(0).end);
        assertEquals(afterCleanup, method.tryCatchBlocks.get(1).end);
    }

    @Test
    void removesAStoreLoadSeparatedByMetadata() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeDeadAdjacentPairs(method));
        assertEquals(0, countLocalAccesses(method, 1));
    }

    @Test
    void removesStaticReceiverStoreWhenArgumentsAreComputedAboveIt() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "([B)[B", null, null);
        LabelNode transparent = new LabelNode();
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("SHA-256"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/security/MessageDigest", "getInstance",
                "(Ljava/lang/String;)Ljava/security/MessageDigest;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(transparent);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/security/MessageDigest", "digest", "([B)[B", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseInvokeCopies(method));
        assertEquals(0, countLocalAccesses(method, 1));
    }

    @Test
    void removesAOneUseFieldReceiverCallAfterPureArgumentSetup() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/lang/Object;Lsample/Holder;)Z", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                "sample/Holder", "store", "Lsample/Store;"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "sample/Store", "value", "()Ljava/lang/String;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseInvokeCopies(method));
        assertEquals(0, countLocalAccesses(method, 2));
    }

    @Test
    void fusesAOneUseConstructorIntoAReferenceInvokeArgument() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/security/cert/CertificateFactory;[B)V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayInputStream"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/io/ByteArrayInputStream", "<init>", "([B)V", false));
        method.instructions.add(new LabelNode());
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/security/cert/CertificateFactory", "generateCertificate",
                "(Ljava/io/InputStream;)Ljava/security/cert/Certificate;", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseConstructorCopies(method));
        assertEquals(0, countLocalAccesses(method, 3));
        assertEquals(1, countOpcode(method, Opcodes.DUP));
        boolean hasByteArrayInputStream = false;
        for (var instruction : method.instructions)
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/io/ByteArrayInputStream"))
                hasByteArrayInputStream = true;
        assertTrue(hasByteArrayInputStream);
    }

    @Test
    void reusesAJoinLocalForAConstructorAcrossAnArgumentBranch() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(ZZ)V", null, null);
        LabelNode alternate = new LabelNode();
        LabelNode join = new LabelNode();
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "sample/Peer"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, alternate));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(alternate);
        method.instructions.add(new InsnNode(Opcodes.ICONST_2));
        method.instructions.add(join);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "sample/Peer", "<init>", "(I)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "sample/Peer", "use", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.mergeConstructorTemporaryIntoJoinLocal(method));
        assertEquals(0, countLocalAccesses(method, 2));
        assertEquals(3, countLocalAccesses(method, 3));
    }

    @Test
    void fusesAConstructedValueIntoItsImmediateInstanceConsumer() {
        MethodNode method = new MethodNode(Opcodes.ASM9, 0,
                "run", "()V", null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "sample/Progress"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "sample/Progress", "<init>", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "sample/Owner", "publish", "(Lsample/Progress;)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseConstructedReceiverCopies(method));
        assertEquals(0, countLocalAccesses(method, 4));
        assertEquals(1, countOpcode(method, Opcodes.DUP));
        assertTrue(method.instructions.indexOf(method.instructions.getFirst())
                < method.instructions.indexOf(method.instructions.getLast()));
    }

    @Test
    void fusesAConstructedValueWhenItsArgumentsWereMaterializedBeforeInit() {
        MethodNode method = new MethodNode(Opcodes.ASM9, 0,
                "run", "()V", null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "sample/Progress"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "sample/Progress", "<init>", "(I)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "sample/Owner", "publish", "(Lsample/Progress;)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseConstructedReceiverCopies(method));
        assertEquals(0, countLocalAccesses(method, 4));
    }

    @Test
    void removesOneUseConstructorArgumentStoresFromAStackResidentObject() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("failure"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseConstructorArgumentCopies(method));
        assertEquals(0, countLocalAccesses(method, 2));
        assertEquals(1, countOpcode(method, Opcodes.DUP));
    }

    @Test
    void retainsMixedWidthConstructorArgumentsUntilStackProofIsAvailable() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "sample/Progress"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("id"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.LCONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.LSTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "sample/Progress", "<init>", "(Ljava/lang/String;J)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(0, JvmLocalMaterializationCleanup.removeOneUseConstructorArgumentCopies(method));
        assertEquals(2, countLocalAccesses(method, 2));
        assertEquals(2, countLocalAccesses(method, 3));
    }

    @Test
    void fusesAOneUseFluentBuilderIntoAStaticConsumer() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder", "<init>", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("left"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("right"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("TAG"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "android/util/Log", "w", "(Ljava/lang/String;Ljava/lang/String;)I", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseFluentBuilderCopies(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(0, countLocalAccesses(method, 2));
        assertEquals(1, countOpcode(method, Opcodes.DUP));
    }

    @Test
    void fusesAOneUseConstructorIntoAThrow() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/io/IOException"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("Missing HELLO"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/io/IOException", "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseConstructorThrows(method));
        assertEquals(0, countLocalAccesses(method, 2));
        assertEquals(1, countOpcode(method, Opcodes.DUP));
    }

    @Test
    void fusesAOneUseStaticProducerIntoAReceiverInvoke() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("SHA-256"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/security/MessageDigest", "getInstance",
                "(Ljava/lang/String;)Ljava/security/MessageDigest;", false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new LabelNode());
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/security/MessageDigest", "digest", "()[B", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseStaticReceiverCopies(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countOpcode(method, Opcodes.INVOKESTATIC));
    }

    @Test
    void retargetsAStoreOnlyHandlerBridgeToAnEquivalentEntry() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode firstStart = new LabelNode();
        LabelNode firstEnd = new LabelNode();
        LabelNode bridge = new LabelNode();
        LabelNode secondStart = new LabelNode();
        LabelNode secondEnd = new LabelNode();
        LabelNode canonical = new LabelNode();
        LabelNode body = new LabelNode();
        method.instructions.add(firstStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(firstEnd);
        method.instructions.add(bridge);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, body));
        method.instructions.add(secondStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(secondEnd);
        method.instructions.add(canonical);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(body);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(firstStart, firstEnd, bridge, null));
        method.tryCatchBlocks.add(new TryCatchBlockNode(secondStart, secondEnd, canonical, null));

        assertEquals(1, JvmLocalMaterializationCleanup.retargetEquivalentHandlerStoreBridges(method));
        assertEquals(canonical, method.tryCatchBlocks.getFirst().handler);
        assertFalse(method.instructions.contains(bridge));
    }

    @Test
    void duplicatesASharedNormalCleanupTailBeforeAHandlerRegion() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/util/Map;Ljava/lang/String;)V", null, null);
        LabelNode normal = new LabelNode();
        LabelNode normalReturn = new LabelNode();
        LabelNode handlerPath = new LabelNode();
        LabelNode handlerReturn = new LabelNode();
        LabelNode sharedTail = new LabelNode();
        method.instructions.add(normal);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, normalReturn));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, sharedTail));
        method.instructions.add(handlerPath);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, handlerReturn));
        method.instructions.add(sharedTail);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "remove",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(normalReturn);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handlerReturn);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.duplicateSharedNormalCleanupTails(method));
        assertEquals(0, countOpcode(method, Opcodes.GOTO));
        assertEquals(4, countMethod(method, "remove"));
    }

    @Test
    void movesAResourceRangeEndBeforeANullCloseGuard() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/io/Closeable;)V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode after = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, after));
        method.instructions.add(end);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/io/Closeable", "close", "()V", true));
        method.instructions.add(after);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));

        assertEquals(1, JvmLocalMaterializationCleanup.normalizeNullCloseRangeEnds(method));
        assertTrue(method.instructions.indexOf(end) < method.instructions.indexOf(method.instructions.get(3)));
    }

    @Test
    void decouplesACloseGuardFromAProtectedBoundary() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/io/Closeable;)V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, end));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/io/Closeable", "close", "()V", true));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));

        assertEquals(1, JvmLocalMaterializationCleanup.decoupleCloseGuardTargets(method));
        assertTrue(method.instructions.indexOf(end) > method.instructions.indexOf(method.instructions.get(4)));
    }

    @Test
    void shapesANullCloseGuardAsAPositiveCloseBranch() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/io/Closeable;)V", null, null);
        LabelNode after = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        JumpInsnNode guard = new JumpInsnNode(Opcodes.IFNONNULL, after);
        method.instructions.add(guard);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/io/Closeable", "close", "()V", true));
        method.instructions.add(after);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.shapeCloseGuardConditionals(method));
        assertEquals(Opcodes.IFNULL, guard.getOpcode());
        assertEquals(0, countOpcode(method, Opcodes.GOTO));
    }

    @Test
    void restoresAJoinAfterAProtectedNullClose() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/io/Closeable;)V", null, null);
        LabelNode start = new LabelNode();
        LabelNode closePath = new LabelNode();
        LabelNode after = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, after));
        method.instructions.add(closePath);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/io/Closeable", "close", "()V", true));
        method.instructions.add(after);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, after, handler, null));
        method.tryCatchBlocks.add(new TryCatchBlockNode(closePath, after, handler, null));

        assertEquals(1, JvmLocalMaterializationCleanup.restoreNullCloseJoinGotos(method));
        int closeEnd = method.instructions.indexOf(after) - 2;
        assertEquals(Opcodes.GOTO, method.instructions.get(closeEnd + 1).getOpcode());
    }

    @Test
    void ordersNestedSameTypeRangesFromInnerToOuter() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode outerStart = new LabelNode();
        LabelNode innerStart = new LabelNode();
        LabelNode innerEnd = new LabelNode();
        LabelNode outerEnd = new LabelNode();
        LabelNode outerHandler = new LabelNode();
        LabelNode innerHandler = new LabelNode();
        method.instructions.add(outerStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(innerStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(innerEnd);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(outerEnd);
        method.instructions.add(innerHandler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(outerHandler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        TryCatchBlockNode outer = new TryCatchBlockNode(outerStart, outerEnd, outerHandler, null);
        TryCatchBlockNode inner = new TryCatchBlockNode(innerStart, innerEnd, innerHandler, null);
        method.tryCatchBlocks.add(outer);
        method.tryCatchBlocks.add(inner);

        assertEquals(1, JvmLocalMaterializationCleanup.orderNestedExceptionRanges(method));
        assertEquals(inner, method.tryCatchBlocks.getFirst());
        assertEquals(outer, method.tryCatchBlocks.get(1));
    }

    @Test
    void normalizesAConditionalThrowWithAForwardSkip() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Z)V", null, null);
        LabelNode throwBlock = new LabelNode();
        LabelNode join = new LabelNode();
        JumpInsnNode branch = new JumpInsnNode(Opcodes.IFEQ, throwBlock);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(branch);
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(throwBlock);
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(join);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.normalizeShortCircuitThrowBlocks(method));
        assertEquals(Opcodes.IFNE, branch.getOpcode());
        assertEquals(join, branch.label);
        assertEquals(0, countOpcode(method, Opcodes.GOTO));
    }

    @Test
    void ordersSharedFinallyRangeBeforeCatchTransfer() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode body = new LabelNode();
        LabelNode bodyEnd = new LabelNode();
        LabelNode catchEntry = new LabelNode();
        LabelNode catchEnd = new LabelNode();
        LabelNode transferEnd = new LabelNode();
        LabelNode finallyEntry = new LabelNode();
        LabelNode finallyEnd = new LabelNode();
        method.instructions.add(body);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(bodyEnd);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(catchEntry);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(catchEnd);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(transferEnd);
        method.instructions.add(finallyEntry);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(finallyEnd);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));

        TryCatchBlockNode outerCatch = new TryCatchBlockNode(body, bodyEnd, catchEntry, null);
        TryCatchBlockNode transfer = new TryCatchBlockNode(catchEntry, transferEnd, finallyEntry, null);
        TryCatchBlockNode outerFinally = new TryCatchBlockNode(body, bodyEnd, finallyEntry, null);
        method.tryCatchBlocks.add(outerCatch);
        method.tryCatchBlocks.add(transfer);
        method.tryCatchBlocks.add(outerFinally);

        assertEquals(1, JvmLocalMaterializationCleanup.orderSharedFinallyTransferRanges(method));
        assertEquals(outerCatch, method.tryCatchBlocks.get(0));
        assertEquals(outerFinally, method.tryCatchBlocks.get(1));
        assertEquals(transfer, method.tryCatchBlocks.get(2));
    }

    @Test
    void protectsSharedFinallyEntryUntilItsExceptionIsStored() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode body = new LabelNode();
        LabelNode bodyEnd = new LabelNode();
        LabelNode catchEntry = new LabelNode();
        LabelNode transferEnd = new LabelNode();
        LabelNode finallyEntry = new LabelNode();
        method.instructions.add(body);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(bodyEnd);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(catchEntry);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, finallyEntry));
        method.instructions.add(transferEnd);
        method.instructions.add(finallyEntry);
        VarInsnNode finallyStore = new VarInsnNode(Opcodes.ASTORE, 2);
        method.instructions.add(finallyStore);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));

        TryCatchBlockNode outerCatch = new TryCatchBlockNode(body, bodyEnd, catchEntry, null);
        TryCatchBlockNode transfer = new TryCatchBlockNode(catchEntry, transferEnd, finallyEntry, null);
        TryCatchBlockNode outerFinally = new TryCatchBlockNode(body, bodyEnd, finallyEntry, null);
        method.tryCatchBlocks.add(outerCatch);
        method.tryCatchBlocks.add(transfer);
        method.tryCatchBlocks.add(outerFinally);

        assertEquals(1, JvmLocalMaterializationCleanup.protectSharedFinallyEntries(method));
        TryCatchBlockNode self = method.tryCatchBlocks.getLast();
        assertEquals(finallyEntry, self.start);
        assertEquals(finallyEntry, self.handler);
        assertTrue(method.instructions.indexOf(self.end) > method.instructions.indexOf(finallyStore));
        assertEquals(0, JvmLocalMaterializationCleanup.protectSharedFinallyEntries(method));
    }

    @Test
    void extendsCatchToFinallyThroughAProvenCleanupTail() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/util/Map;Ljava/lang/String;)V", null, null);
        LabelNode body = new LabelNode();
        LabelNode bodyEnd = new LabelNode();
        LabelNode catchEntry = new LabelNode();
        LabelNode catchEnd = new LabelNode();
        LabelNode cleanup = new LabelNode();
        LabelNode finallyEntry = new LabelNode();
        method.instructions.add(body);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(bodyEnd);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(catchEntry);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(catchEnd);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(cleanup);
        for (int i = 0; i < 2; i++) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "remove",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", true));
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(finallyEntry);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        TryCatchBlockNode outer = new TryCatchBlockNode(body, bodyEnd, finallyEntry, null);
        TryCatchBlockNode catchRange = new TryCatchBlockNode(catchEntry, catchEnd, finallyEntry, null);
        method.tryCatchBlocks.add(outer);
        method.tryCatchBlocks.add(catchRange);

        assertEquals(1, JvmLocalMaterializationCleanup.extendCatchToFinallyRanges(method));
        assertEquals(cleanup, catchRange.end);
    }

    @Test
    void removesOnlyAContainedRangeWithTheSameHandler() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode outerStart = new LabelNode();
        LabelNode innerStart = new LabelNode();
        LabelNode innerEnd = new LabelNode();
        LabelNode outerEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(outerStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(innerStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(innerEnd);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(outerEnd);
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        TryCatchBlockNode outer = new TryCatchBlockNode(outerStart, outerEnd, handler, null);
        TryCatchBlockNode inner = new TryCatchBlockNode(innerStart, innerEnd, handler, null);
        method.tryCatchBlocks.add(outer);
        method.tryCatchBlocks.add(inner);

        assertEquals(1, JvmLocalMaterializationCleanup.removeRedundantContainedExceptionRanges(method));
        assertEquals(1, method.tryCatchBlocks.size());
        assertEquals(outer, method.tryCatchBlocks.getFirst());
    }

    @Test
    void removesAnImmediatelyConsumedBooleanBranchStore() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()Z", null, null);
        LabelNode done = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(done);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeBooleanStoreLoadsBeforeBranches(method));
        assertEquals(0, countLocalAccesses(method, 1));
    }

    @Test
    void retainsBooleanMaterializationWhenTheFallthroughReadsItAgain() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()I", null, null);
        LabelNode done = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(done);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));

        assertEquals(0, JvmLocalMaterializationCleanup.removeBooleanStoreLoadsBeforeBranches(method));
        assertEquals(3, countLocalAccesses(method, 1));
    }

    @Test
    void widensOnlyTheBroadOuterRangeToAResourceAlias() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(Ljava/io/Closeable;)V", null, null);
        LabelNode body = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode nestedEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(body);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/io/Closeable", "close", "()V", true));
        method.instructions.add(nestedEnd);
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        TryCatchBlockNode broad = new TryCatchBlockNode(body, end, handler, null);
        TryCatchBlockNode nested = new TryCatchBlockNode(body, nestedEnd, handler, null);
        method.tryCatchBlocks.add(broad);
        method.tryCatchBlocks.add(nested);

        assertEquals(1, JvmLocalMaterializationCleanup.widenOuterRangesToResourceAlias(method));
        assertTrue(method.instructions.indexOf(broad.start) < method.instructions.indexOf(body));
        assertEquals(body, nested.start);
    }

    @Test
    void doesNotCrossAControlFlowLabel() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode boundary = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, boundary));
        method.instructions.add(boundary);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(0, JvmLocalMaterializationCleanup.removeDeadAdjacentPairs(method));
        assertEquals(2, countLocalAccesses(method, 1));
    }

    @Test
    void permitsADeadValueToBeOverwrittenOnTheSameStraightLinePath() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeDeadAdjacentPairs(method));
        assertEquals(1, countLocalAccesses(method, 1));
    }

    @Test
    void removesAStableStraightLineLocalCopy() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeRedundantLocalCopies(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countLocalAccesses(method, 0));
    }

    @Test
    void removesAStoreReloadStoreWhenTheSourceIsNotReadAgain() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeRedundantStoreReloadStores(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countLocalAccesses(method, 2));
    }

    @Test
    void removesADeadRelayEvenWhenLaterControlFlowIsLooped() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode loop = new LabelNode();
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        method.instructions.add(loop);
        method.instructions.add(new IincInsnNode(3, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPLT, loop));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeRedundantStoreReloadStores(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countLocalAccesses(method, 2));
    }

    @Test
    void removesAOneUseRawReferenceBeforeItsCast() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "Owner"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeRedundantCastStores(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countLocalAccesses(method, 2));
        assertEquals(1, countOpcode(method, Opcodes.CHECKCAST));
    }

    @Test
    void retainsRawCastSourceWhenAHandlerCanReadIt() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "Owner"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));

        assertEquals(0, JvmLocalMaterializationCleanup.removeRedundantCastStores(method));
        assertEquals(3, countLocalAccesses(method, 1));
    }

    @Test
    void foldsAStandaloneUnconditionalCopyBridgeIntoItsIncomingEdge() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode bridge = new LabelNode();
        LabelNode target = new LabelNode();
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, bridge));
        method.instructions.add(bridge);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, target));
        method.instructions.add(target);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeUnconditionalCopyBridges(method));
        assertFalse(method.instructions.contains(bridge));
        assertEquals(2, countLocalAccesses(method, 1));
    }

    @Test
    void foldsAConditionalSingleIncomingCopyBridgeIntoItsTarget() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(ZLjava/lang/Object;)V", null, null);
        LabelNode bridge = new LabelNode();
        LabelNode target = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, bridge));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(bridge);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, target));
        method.instructions.add(target);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeSingleIncomingCopyBridges(method));
        assertFalse(method.instructions.contains(bridge));
        assertEquals(0, countLocalAccesses(method, 2));
        assertEquals(1, countLocalAccesses(method, 1));
    }

    @Test
    void composesCopyPhiWithAfallthroughFinalValue() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(ZLjava/lang/Object;Ljava/lang/Object;)V", null, null);
        LabelNode second = new LabelNode();
        LabelNode first = new LabelNode();
        LabelNode join = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, second));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, first));
        method.instructions.add(second);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(first);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(join);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeCopyPhiJoins(method));
        assertEquals(0, countLocalAccesses(method, 3));
        assertEquals(3, countLocalAccesses(method, 4));
    }

    @Test
    void composesTwoCopyEdgesThroughAMultiSourceJoin() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(ZLjava/lang/Object;Ljava/lang/Object;)V", null, null);
        LabelNode second = new LabelNode();
        LabelNode first = new LabelNode();
        LabelNode join = new LabelNode();
        LabelNode target = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, second));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, first));
        method.instructions.add(second);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(first);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(join);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, target));
        method.instructions.add(target);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeMultiSourceCopyBridges(method));
        assertFalse(method.instructions.contains(join));
        assertEquals(0, countLocalAccesses(method, 3));
        assertEquals(3, countLocalAccesses(method, 4));
    }

    @Test
    void composesAfallthroughCopyEdgeThroughAMultiSourceJoin() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "(ZLjava/lang/Object;Ljava/lang/Object;)V", null, null);
        LabelNode second = new LabelNode();
        LabelNode first = new LabelNode();
        LabelNode join = new LabelNode();
        LabelNode target = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, second));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, first));
        method.instructions.add(second);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, join));
        method.instructions.add(first);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(join);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, target));
        method.instructions.add(target);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeFallthroughCopyJoins(method));
        assertFalse(method.instructions.contains(join));
        assertEquals(0, countLocalAccesses(method, 3));
        assertEquals(3, countLocalAccesses(method, 4));
    }

    @Test
    void reemitsAOneUseInstanceFieldAtItsConsumer() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                "run", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "Owner", "value",
                "Ljava/lang/Object;"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseFieldCopies(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countOpcode(method, Opcodes.GETFIELD));
    }

    @Test
    void removesAOneUseFieldAliasBeforeAFrameInASplitLayout() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                "run", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "Owner", "value",
                "Ljava/lang/Object;"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseFieldCopies(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countOpcode(method, Opcodes.GETFIELD));
    }

    @Test
    void removesAOneUseFieldAssignmentTemporary() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "Owner", "value",
                "Ljava/lang/Object;"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeOneUseFieldAssignmentLocals(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countOpcode(method, Opcodes.SWAP));
    }

    @Test
    void removesADeadFieldReadAliasWhenTheFieldIsReadAgain() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                "run", "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "Owner", "value",
                "Ljava/lang/Object;"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "Owner", "value",
                "Ljava/lang/Object;"));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeRedundantFieldReadAliases(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(1, countOpcode(method, Opcodes.GETFIELD));
    }

    @Test
    void doesNotCopyAcrossAControlFlowBoundary() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode boundary = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, boundary));
        method.instructions.add(boundary);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(0, JvmLocalMaterializationCleanup.removeRedundantLocalCopies(method));
        assertEquals(2, countLocalAccesses(method, 1));
    }

    @Test
    void permitsProtectedBodyCleanupWhenTheHandlerCannotObserveTheLocal() {
        MethodNode method = protectedPairMethod(false);

        assertEquals(1, JvmLocalMaterializationCleanup.removeDeadAdjacentPairs(method));
        assertEquals(0, countLocalAccesses(method, 1));
    }

    @Test
    void crossesAnUnreferencedFallthroughLabel() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new LabelNode());
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        assertEquals(1, JvmLocalMaterializationCleanup.removeDeadAdjacentPairs(method));
        assertEquals(0, countLocalAccesses(method, 1));
    }

    @Test
    void retainsProtectedBodyCleanupWhenTheHandlerReadsTheLocal() {
        MethodNode method = protectedPairMethod(true);

        assertEquals(0, JvmLocalMaterializationCleanup.removeDeadAdjacentPairs(method));
        assertEquals(3, countLocalAccesses(method, 1));
    }

    @Test
    void coalescesEquivalentOneCopyCatchRoutes() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode firstStart = new LabelNode();
        LabelNode firstEnd = new LabelNode();
        LabelNode secondStart = new LabelNode();
        LabelNode secondEnd = new LabelNode();
        LabelNode firstHandler = new LabelNode();
        LabelNode secondHandler = new LabelNode();
        LabelNode firstGlue = new LabelNode();
        LabelNode secondGlue = new LabelNode();
        LabelNode common = new LabelNode();
        LabelNode done = new LabelNode();

        method.instructions.add(firstStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
        method.instructions.add(firstEnd);
        method.instructions.add(secondStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
        method.instructions.add(secondEnd);
        method.instructions.add(firstHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, firstGlue));
        method.instructions.add(firstGlue);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(secondHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, secondGlue));
        method.instructions.add(secondGlue);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(common);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(done);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(firstStart, firstEnd, firstHandler, null));
        method.tryCatchBlocks.add(new TryCatchBlockNode(secondStart, secondEnd, secondHandler, null));

        assertEquals(1, JvmLocalMaterializationCleanup.coalesceOneCopyHandlerRoutes(method));
        assertEquals(firstHandler, method.tryCatchBlocks.get(1).handler);
        assertFalse(method.instructions.contains(secondHandler));
        assertFalse(method.instructions.contains(secondGlue));
    }

    @Test
    void collapsesInitialHandlerTemporaryWhenItIsRouteLocal() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode glue = new LabelNode();
        LabelNode common = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(end);
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, glue));
        method.instructions.add(glue);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(common);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));

        assertEquals(2, JvmLocalMaterializationCleanup.collapseInitialHandlerCopyChains(method));
        assertEquals(0, countLocalAccesses(method, 1));
        assertEquals(2, countLocalAccesses(method, 2));
    }

    @Test
    void coalescesEquivalentTwoCopyCatchRoutesWithSameDestinations() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode firstStart = new LabelNode();
        LabelNode firstEnd = new LabelNode();
        LabelNode secondStart = new LabelNode();
        LabelNode secondEnd = new LabelNode();
        LabelNode firstHandler = new LabelNode();
        LabelNode secondHandler = new LabelNode();
        LabelNode firstGlue = new LabelNode();
        LabelNode secondGlue = new LabelNode();
        LabelNode common = new LabelNode();
        LabelNode done = new LabelNode();

        method.instructions.add(firstStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
        method.instructions.add(firstEnd);
        method.instructions.add(secondStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
        method.instructions.add(secondEnd);
        method.instructions.add(firstHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, firstGlue));
        method.instructions.add(firstGlue);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(secondHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, secondGlue));
        method.instructions.add(secondGlue);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(common);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(done);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(firstStart, firstEnd, firstHandler, null));
        method.tryCatchBlocks.add(new TryCatchBlockNode(secondStart, secondEnd, secondHandler, null));

        assertEquals(1, JvmLocalMaterializationCleanup.coalesceTwoCopyHandlerRoutes(method));
        assertEquals(firstHandler, method.tryCatchBlocks.get(1).handler);
        assertFalse(method.instructions.contains(secondHandler));
        assertFalse(method.instructions.contains(secondGlue));
    }

    @Test
    void doesNotCoalesceTwoCopyRoutesWithDifferentDestinationSequences() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode firstStart = new LabelNode();
        LabelNode firstEnd = new LabelNode();
        LabelNode secondStart = new LabelNode();
        LabelNode secondEnd = new LabelNode();
        LabelNode firstHandler = new LabelNode();
        LabelNode secondHandler = new LabelNode();
        LabelNode firstGlue = new LabelNode();
        LabelNode secondGlue = new LabelNode();
        LabelNode common = new LabelNode();
        LabelNode done = new LabelNode();

        method.instructions.add(firstStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
        method.instructions.add(firstEnd);
        method.instructions.add(secondStart);
        method.instructions.add(new InsnNode(Opcodes.NOP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, done));
        method.instructions.add(secondEnd);
        method.instructions.add(firstHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, firstGlue));
        method.instructions.add(firstGlue);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(secondHandler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, secondGlue));
        method.instructions.add(secondGlue);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, common));
        method.instructions.add(common);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(done);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(firstStart, firstEnd, firstHandler, null));
        method.tryCatchBlocks.add(new TryCatchBlockNode(secondStart, secondEnd, secondHandler, null));

        assertEquals(0, JvmLocalMaterializationCleanup.coalesceTwoCopyHandlerRoutes(method));
        assertEquals(secondHandler, method.tryCatchBlocks.get(1).handler);
        assertTrue(method.instructions.contains(secondHandler));
        assertTrue(method.instructions.contains(secondGlue));
    }

    private static int countLocalAccesses(MethodNode method, int local) {
        int count = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof VarInsnNode variable && variable.var == local)
                count++;
        return count;
    }

    private static int countOpcode(MethodNode method, int opcode) {
        int count = 0;
        for (var instruction : method.instructions)
            if (instruction.getOpcode() == opcode) count++;
        return count;
    }

    private static int countMethod(MethodNode method, String name) {
        int count = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode invoke && invoke.name.equals(name)) count++;
        return count;
    }

    private static MethodNode protectedPairMethod(boolean handlerReadsLocal) {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        if (handlerReadsLocal) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        method.maxStack = 1;
        method.maxLocals = 3;
        return method;
    }
}
