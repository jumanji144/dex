package me.darknet.dex.tree.codec;

/**
 * Codec to map from one type to another.
 * @param <I> the input type
 * @param <O> the output type
 */
public interface MappingCodec<I, O> {

    O map(I input);

    I unmap(O output);

}
