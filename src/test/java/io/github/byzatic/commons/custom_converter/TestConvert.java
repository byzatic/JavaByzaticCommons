/*
 *    Copyright 2021 Konstantin Fedorov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.byzatic.commons.custom_converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("test convert ")
public class TestConvert {
    public static final Logger logger = LoggerFactory.getLogger(TestConvert.class);

    @Test
    @DisplayName("fields as list")
    public void testListObjectsConvert() throws DataConvertException {
        ListTest1 expected = new ListTest1();
        expected.setA(Arrays.asList(1, 2, 3, 4));
        ListTest2 actual = CustomConverter.parse(expected, ListTest2.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(actual.getA(), expected.getA());
    }

    @Test
    @DisplayName("fields as array")
    public void testArrayObjectsConvert() throws DataConvertException {
        ArrayTest1 expected = new ArrayTest1();
        expected.setA(new int[]{1, 2, 3, 4});
        ArrayTest2 actual = CustomConverter.parse(expected, ArrayTest2.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(actual.getA(), expected.getA());
    }

    @Test
    @DisplayName("int to Integer")
    public void testPrimitivesInt() throws DataConvertException {
        int expected = Integer.MAX_VALUE;
        Integer actual = CustomConverter.parse(expected, Integer.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Integer.compare(actual, expected));
    }

    @Test
    @DisplayName("float to Float")
    public void testPrimitivesFloat() throws DataConvertException {
        float expected = Float.MAX_VALUE;
        Float actual = CustomConverter.parse(expected, Float.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Double.compare(actual, expected));
    }

    @Test
    @DisplayName("long to Long")
    public void testPrimitivesLong() throws DataConvertException {
        long expected = 1778509127;
        Long actual = CustomConverter.parse(expected, Long.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Long.compare(actual, expected));
    }

    @Test
    @DisplayName("double to Double")
    public void testPrimitivesDouble() throws DataConvertException {
        double expected = Double.MAX_VALUE;
        Double actual = CustomConverter.parse(expected, Double.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Double.compare(actual, expected));
    }

    @Test
    @DisplayName("boolean to Boolean")
    public void testPrimitivesBoolean() throws DataConvertException {
        boolean expected = true;
        Boolean actual = CustomConverter.parse(expected, Boolean.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Boolean.compare(actual, expected));
    }

    @Test
    @DisplayName("short to Short")
    public void testPrimitivesShort() throws DataConvertException {
        short expected = Short.MAX_VALUE;
        Short actual = CustomConverter.parse(expected, Short.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Short.compare(actual, expected));
    }

    @Test
    @DisplayName("char to Character")
    public void testPrimitivesCharacter() throws DataConvertException {
        char expected = Character.MAX_VALUE;
        Character actual = CustomConverter.parse(expected, Character.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Character.compare(actual, expected));
    }

    @Test
    @DisplayName("byte to Byte")
    public void testPrimitivesByte() throws DataConvertException {
        byte expected = Byte.MAX_VALUE;
        Byte actual = CustomConverter.parse(expected, Byte.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(0, Byte.compare(actual, expected));
    }

    @Test
    @DisplayName("not annotated to annotated fields")
    public void testAnnotations() throws DataConvertException {
        AnnotationsTest2 expected = new AnnotationsTest2();
        expected.setId(12321);
        AnnotationsTest1 actual = CustomConverter.parse(expected, AnnotationsTest1.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(actual.getIp(), expected.getId());
    }

    @Test
    @DisplayName("annotated to not annotated fields")
    public void testAnnotations2() throws DataConvertException {
        AnnotationsTest1 expected = new AnnotationsTest1();
        expected.setIp(12321);
        AnnotationsTest2 actual = CustomConverter.parse(expected, AnnotationsTest2.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(actual.getId(), expected.getIp());
    }

    @Test
    @DisplayName("annotated to annotated fields")
    public void testAnnotations3() throws DataConvertException {
        AnnotationsTest2 expected = new AnnotationsTest2();
        expected.setId(12321);
        AnnotationsTest3 actual = CustomConverter.parse(expected, AnnotationsTest3.class);

        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(actual.getIq(), expected.getId());
    }

    @Test
    @DisplayName("enum to enum")
    public void testEnum() throws DataConvertException {
        EnumTest1 expected = new EnumTest1();
        expected.setA(Enum1.a);
        EnumTest2 actual = CustomConverter.parse(expected, EnumTest2.class);
        logger.debug("expected: {}", expected);
        logger.debug("actual: {}", actual);

        assertEquals(actual.getA().name(), expected.getA().name());
    }

    enum Enum1 {
        a,
        b
    }

    enum Enum2 {
        a,
        b
    }

    static class ListTest1 {
        public List<Integer> a;

        public List<Integer> getA() {
            return a;
        }

        public ListTest1 setA(List<Integer> a) {
            this.a = a;
            return this;
        }

        @Override
        public String toString() {
            return "ListTest1{" +
                    "a=" + a +
                    '}';
        }
    }

    static class ListTest2 {
        public List<Integer> a;

        public List<Integer> getA() {
            return a;
        }

        @Override
        public String toString() {
            return "ListTest2{" +
                    "a=" + a +
                    '}';
        }
    }

    static class ArrayTest1 {
        public int[] a;

        public int[] getA() {
            return a;
        }

        public ArrayTest1 setA(int[] a) {
            this.a = a;
            return this;
        }

        @Override
        public String toString() {
            return "ArrayTest1{" +
                    "a=" + Arrays.toString(a) +
                    '}';
        }
    }

    static class ArrayTest2 {
        public int[] a;

        public int[] getA() {
            return a;
        }

        @Override
        public String toString() {
            return "ArrayTest2{" +
                    "a=" + Arrays.toString(a) +
                    '}';
        }
    }

    static class AnnotationsTest1 {
        @Reflectable(name = "id")
        int ip;

        public int getIp() {
            return ip;
        }

        public AnnotationsTest1 setIp(int ip) {
            this.ip = ip;
            return this;
        }

        @Override
        public String toString() {
            return "AnnotationsTest1{" +
                    "ip=" + ip +
                    '}';
        }
    }

    static class AnnotationsTest2 {
        int id;

        public int getId() {
            return id;
        }

        public AnnotationsTest2 setId(int id) {
            this.id = id;
            return this;
        }

        @Override
        public String toString() {
            return "AnnotationsTest2{" +
                    "id=" + id +
                    '}';
        }
    }

    static class AnnotationsTest3 {
        @Reflectable(name = "id")
        int iq;

        public int getIq() {
            return iq;
        }

        public AnnotationsTest3 setIq(int iq) {
            this.iq = iq;
            return this;
        }

        @Override
        public String toString() {
            return "AnnotationsTest3{" +
                    "iq=" + iq +
                    '}';
        }
    }

    static class EnumTest1 {
        private Enum1 a;

        public Enum1 getA() {
            return a;
        }

        public void setA(Enum1 a) {
            this.a = a;
        }
    }

    static class EnumTest2 {
        private Enum2 a;

        public Enum2 getA() {
            return a;
        }
    }
}
