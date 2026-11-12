package com.example.PhanTichAI.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Every table of database/schema.sql has exactly one entity, and the entity maps exactly the table's columns. */
class EntityMappingTest {
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*(\\w+)\\s+(?:INT|BIGINT|VARCHAR|NVARCHAR|NUMERIC|BIT|DATETIME2|DATE)\\b", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    @Test
    void everyTableHasOneEntityWithTheSameColumns() throws Exception {
        Map<String, Set<String>> schema = schemaColumns();
        Map<String, Set<String>> entities = entityColumns();
        assertThat(schema).hasSize(15);
        assertThat(entities.keySet()).containsExactlyInAnyOrderElementsOf(schema.keySet());
        schema.forEach((table, columns) ->
                assertThat(entities.get(table)).as("columns of " + table).containsExactlyInAnyOrderElementsOf(columns));
    }

    private static Map<String, Set<String>> schemaColumns() throws Exception {
        Path file = Path.of(System.getProperty("user.dir")).resolve("../database/schema.sql").normalize();
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Set<String>> result = new TreeMap<>();
        Matcher table = TABLE.matcher(sql);
        while (table.find()) {
            Set<String> columns = new TreeSet<>();
            Matcher column = COLUMN.matcher(table.group(2));
            while (column.find()) columns.add(column.group(1).toLowerCase(Locale.ROOT));
            result.put(table.group(1).toLowerCase(Locale.ROOT), columns);
        }
        return result;
    }

    private static Map<String, Set<String>> entityColumns() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Map<String, Set<String>> result = new TreeMap<>();
        for (var candidate : scanner.findCandidateComponents("com.example.PhanTichAI")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            assertThat(type.getPackageName()).as(type.getSimpleName()).isEqualTo("com.example.PhanTichAI.entity");
            String table = type.getAnnotation(Table.class).name().toLowerCase(Locale.ROOT);
            Set<String> columns = new TreeSet<>();
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Column column = field.getAnnotation(Column.class);
                columns.add((column != null && !column.name().isEmpty() ? column.name() : field.getName()).toLowerCase(Locale.ROOT));
            }
            assertThat(result.put(table, columns)).as("two entities for " + table).isNull();
        }
        return result;
    }
}
