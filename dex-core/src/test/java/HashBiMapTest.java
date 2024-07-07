import me.darknet.dex.collections.BiMap;
import me.darknet.dex.collections.HashBiMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HashBiMapTest {

    @Test
    public void testBiMap() {

        BiMap<Integer, String> map = new HashBiMap<>();

        map.put(1, "one");
        map.put(2, "two");

        map.putValue("three", 3);
        map.putValue("four", 4);

        BiMap<String, Integer> inverse = map.inverse();

        Assertions.assertEquals(4, map.size());
        Assertions.assertEquals(4, inverse.size());

        Assertions.assertEquals("one", map.get(1));
        Assertions.assertEquals(1, map.getKey("one"));

        map.remove(1);
        Assertions.assertNull(map.get(1));

        map.removeValue("two");
        Assertions.assertNull(map.getKey("two"));

        map.clear();

        Assertions.assertTrue(map.isEmpty());

    }

}
