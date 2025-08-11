package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JobRecordTest {

    @Test
    void hasExpectedFields() throws Exception {
        Class<?> cls = Class.forName("io.github.byzatic.commons.schedulers.immediate.JobRecord");

        // ожидаемые поля по контракту (id/state/lastStart/lastEnd/lastError/runningFuture/tokenRef/removed)
        Set<String> expected = new HashSet<>(Arrays.asList(
                "id", "state", "lastStart", "lastEnd", "lastError", "runningFuture", "tokenRef", "removed"
        ));

        Set<String> actual = new HashSet<>();
        for (Field f : cls.getDeclaredFields()) {
            actual.add(f.getName());
        }

        // не валим тест, если структура немного отличается — но проверим, что ключевые поля есть
        for (String name : expected) {
            assertTrue(actual.contains(name), "Missing field in JobRecord: " + name);
        }
    }
}
