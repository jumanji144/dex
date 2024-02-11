package me.darknet.dex.io;

import java.io.IOException;

public record Sections(Output stringIds, Output typeIds, Output protoIds, Output fieldIds,
                       Output methodIds, Output classDefs, Output callSiteIds, Output methodHandles,
                       Output data, Output map, Output link) {

    public Sections(Output output) {
        this(output.newOutput(), output.newOutput(), output.newOutput(), output.newOutput(),
                output.newOutput(), output.newOutput(), output.newOutput(), output.newOutput(),
                output.newOutput(), output.newOutput(), output.newOutput());
    }

    public void write(Output output) throws IOException {
        output.write(stringIds);
        output.write(typeIds);
        output.write(protoIds);
        output.write(fieldIds);
        output.write(methodIds);
        output.write(classDefs);
        output.write(callSiteIds);
        output.write(methodHandles);
        output.write(data);
        output.write(map);
        output.write(link);
    }

    public int size() {
        return stringIds.position() + typeIds.position() + protoIds.position() + fieldIds.position() +
                methodIds.position() + classDefs.position() + callSiteIds.position() + methodHandles.position() +
                data.position() + map.position() + link.position();
    }


}
