package com.millionnaire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 扫描引擎主代码，禁止破坏确定性的 API：系统随机数、系统时钟、哈希遍历顺序集合、随机化遍历的不可变集合、浮点、并行流。
 * 注释与字符串字面量在扫描前被剔除。
 */
class SourceScanTest {
    private static final Path MAIN = Path.of("src", "main", "java");

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\bjava\\.util\\.Random\\b"),
            Pattern.compile("\\bRandom\\b"),
            Pattern.compile("\\bMath\\s*\\.\\s*random\\b"),
            Pattern.compile("\\bThreadLocalRandom\\b"),
            Pattern.compile("\\bSplittableRandom\\b"),
            Pattern.compile("\\bSecureRandom\\b"),
            Pattern.compile("\\bSystem\\s*\\.\\s*(currentTimeMillis|nanoTime)\\b"),
            Pattern.compile("\\b(Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime)\\s*\\.\\s*now\\b"),
            Pattern.compile("\\bClock\\b"),
            Pattern.compile("\\b(HashMap|HashSet|Hashtable|IdentityHashMap|WeakHashMap)\\b"),
            Pattern.compile("\\b(Map|Set)\\s*\\.\\s*(of|copyOf|ofEntries)\\b"),
            Pattern.compile("\\b(double|float|Double|Float)\\b"),
            Pattern.compile("\\bparallelStream\\b|\\.parallel\\s*\\("),
            Pattern.compile("^\\s*import\\s+java\\.util\\.\\*\\s*;", Pattern.MULTILINE),
            // 演示领域只允许存在于测试夹具
            Pattern.compile("\\bDemo[A-Z]\\w*|\\bDEMO_\\w*"));

    @Test
    void engineMainCodeAvoidsNonDeterministicApis() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(files.size() > 20, "scanner must see the engine sources, found " + files.size());
        for (Path f : files) {
            String code = stripCommentsAndStrings(Files.readString(f, StandardCharsets.UTF_8));
            for (Pattern p : FORBIDDEN) {
                if (p.matcher(code).find()) {
                    violations.add(MAIN.relativize(f) + " matches " + p.pattern());
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void scannerCatchesForbiddenUsage() {
        String bad = "class X { long t = System.currentTimeMillis(); java.util.Random r = new Random(); }";
        assertTrue(FORBIDDEN.stream().anyMatch(p -> p.matcher(stripCommentsAndStrings(bad)).find()));
        String commented = "class X { /* new Random() */ // Math.random()\n String s = \"HashMap\"; }";
        assertTrue(FORBIDDEN.stream().noneMatch(p -> p.matcher(stripCommentsAndStrings(commented)).find()));
    }

    /** 剔除块注释、行注释与字符串/字符字面量。 */
    static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < src.length() && src.charAt(i) != c) {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append("\"\"");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
