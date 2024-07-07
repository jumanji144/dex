package me.darknet.dex.tree.codec;

public interface ContextMappingCodec<I, O, CI, CO> {

    default O map(I input) {
        return map(input, null);
    }

    default I unmap(O output) {
        return unmap(output, null);
    }

    default O map(I input, CI context) {
        return map(input);
    }

    default I unmap(O output, CO context) {
        return unmap(output);
    }

}
