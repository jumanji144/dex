package me.darknet.dex.convert;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.io.Input;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import me.darknet.dex.convert.util.Decompile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class ReceiveOfferDumpTest {
    @Test
    void dumpCurrentReceiveOffer() throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), "test-data", "samples",
                "REAL-FileTransfer", "classes5.dex");
        if (!Files.exists(path)) path = Paths.get("..", "test-data", "samples", "REAL-FileTransfer", "classes5.dex").normalize();
        Input input = Input.wrap(Files.readAllBytes(path));
        DexHeader header = DexHeader.CODEC.read(input);
        DexFile dex = DexFile.CODEC.map(header, header.map());
        DexConversionIr conversion = new DexConversionIr();
        conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
        ConversionResult result = conversion.toClasses(dex);
        String owner = "com/example/imageserver/transfer/TransferService";
        byte[] bytes = result.classes().get(owner);
        String source = Decompile.decompile(owner, bytes);
        int start = source.indexOf("private void receiveOffer");
        int end = source.indexOf("\n    private ", start + 1);
        if (end < 0) end = source.length();
        Path output = Paths.get("build", "tmp", "current-receiveOffer");
        Files.createDirectories(output);
        Files.writeString(output.resolve("TransferService.java"), source.substring(start, end));
        Files.writeString(output.resolve("TransferService.bytecode.txt"), Decompile.bytecode(bytes));
        Files.writeString(output.resolve("diagnostics.txt"), result.diagnostics().values().stream()
                .flatMap(java.util.Collection::stream).map(Object::toString).reduce("", (a, b) -> a + b + "\n"));
        Decompile.verify(bytes);
    }
}
