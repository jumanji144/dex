package me.darknet.dex.tree;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.definitions.ClassDefinition;

import java.util.ArrayList;
import java.util.List;

public record DexFile(int version, List<ClassDefinition> definitions, byte[] link) {

    public static final TreeCodec<DexFile, DexHeader> CODEC = new TreeCodec<>() {

        @Override
        public DexFile map(DexHeader input) {
            List<ClassDefinition> definitions = new ArrayList<>();
            for (ClassDefItem item : input.map().classes()) {
                definitions.add(ClassDefinition.CODEC.map(item, input.map()));
            }
            return new DexFile(input.version(), definitions, input.link());
        }

        @Override
        public DexHeader unmap(DexFile output) {
            DexMapBuilder builder = new DexMapBuilder();
            for (ClassDefinition definition : output.definitions) {
                ClassDefItem item = ClassDefinition.CODEC.unmap(definition, builder);
                builder.classes().add(item);
            }
            return new DexHeader(output.version(), output.link(), builder.build());
        }

    };

}
