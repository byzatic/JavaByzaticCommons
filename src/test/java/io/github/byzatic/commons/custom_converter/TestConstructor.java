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

import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestConstructor {
    @Test
    @DisplayName("convert with 1 parameter constructor")
    void convertParameterizedConstructor() {
        assertThrows(DataConvertException.class,
                ()-> CustomConverter.parse(new ConstructorTest1(1), ConstructorTest2.class));
    }

    static class ConstructorTest1{
        private int a;

        public ConstructorTest1(int a) {
            this.a = a;
        }
    }

    static class ConstructorTest2{
        private int a;

        public ConstructorTest2(int a) {
            this.a = a;
        }
    }
}

