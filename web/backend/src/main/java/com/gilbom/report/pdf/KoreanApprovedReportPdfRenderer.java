package com.gilbom.report.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.report.ReportRecord;

/**
 * 외부 실행기나 HTML을 사용하지 않는 고정 PDF renderer.
 *
 * <p>본문은 hex text operand로만 기록하고 Adobe-Korea1 predefined CMap을
 * 사용한다. 따라서 보고서 문자열이 PDF 명령으로 해석되지 않으며 한글과 영문을
 * 같은 승인 artifact에 보존할 수 있다.</p>
 */
@Component
public class KoreanApprovedReportPdfRenderer
        implements ApprovedReportPdfRenderer {

    private static final int MAXIMUM_CODE_POINTS_PER_LINE = 52;
    private static final int LINES_PER_PAGE = 48;

    private final ObjectMapper objectMapper;

    public KoreanApprovedReportPdfRenderer(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] render(
            ReportRecord report,
            UUID approvedBy,
            Instant approvedAt
    ) {
        List<String> lines = new ArrayList<>();
        add(lines, "길봄 승인 보고서");
        add(lines, "보고서 코드: " + report.reportCode());
        add(lines, "승인 버전: " + report.currentVersionNo());
        add(lines, "승인자: " + approvedBy);
        add(lines, "승인 시각: " + approvedAt);
        add(lines, "보고 기간: "
                + report.periodFrom() + " ~ " + report.periodTo());
        add(lines, "보고서 유형: " + report.reportType());
        add(lines, "생성 방식: "
                + report.currentVersionGenerationMethod());
        if (report.llmModel() != null) {
            add(lines, "LLM 모델: " + report.llmModel());
        }
        if (report.promptVersion() != null) {
            add(lines, "Prompt 버전: " + report.promptVersion());
        }
        add(lines, "");
        add(lines, "제목");
        add(lines, report.title());
        add(lines, "");
        add(lines, "요약");
        add(lines, report.summary() == null ? "-" : report.summary());
        add(lines, "");
        add(lines, "통계 snapshot");
        add(lines, json(report.statistics()));
        add(lines, "");
        add(lines, "근거 snapshot");
        add(lines, json(report.evidenceSnapshot()));
        add(lines, "");
        add(lines, "본문");
        add(lines, report.body());

        return pdf(lines);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored report JSON could not be rendered",
                    exception);
        }
    }

    private static void add(List<String> target, String value) {
        String sanitized = sanitize(value);
        String[] logicalLines = sanitized.split("\\n", -1);
        for (String logicalLine : logicalLines) {
            if (logicalLine.isEmpty()) {
                target.add(" ");
                continue;
            }
            int offset = 0;
            while (offset < logicalLine.length()) {
                int remaining = logicalLine.codePointCount(
                        offset,
                        logicalLine.length());
                int count = Math.min(
                        remaining,
                        MAXIMUM_CODE_POINTS_PER_LINE);
                int end = logicalLine.offsetByCodePoints(offset, count);
                target.add(logicalLine.substring(offset, end));
                offset = end;
            }
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\r') {
                return;
            }
            if (codePoint == '\n') {
                result.append('\n');
            } else if (codePoint == '\t') {
                result.append("    ");
            } else if (Character.isISOControl(codePoint)
                    || codePoint > Character.MAX_VALUE) {
                result.append('\uFFFD');
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private static byte[] pdf(List<String> lines) {
        List<List<String>> pages = new ArrayList<>();
        for (int offset = 0; offset < lines.size();
                offset += LINES_PER_PAGE) {
            pages.add(List.copyOf(lines.subList(
                    offset,
                    Math.min(offset + LINES_PER_PAGE, lines.size()))));
        }
        if (pages.isEmpty()) {
            pages.add(List.of(" "));
        }

        int fontObject = 3;
        int firstPageObject = 5;
        List<byte[]> objects = new ArrayList<>();
        objects.add(null);
        objects.add(ascii(
                "<< /Type /Catalog /Pages 2 0 R >>"));

        StringBuilder kids = new StringBuilder();
        for (int index = 0; index < pages.size(); index++) {
            kids.append(firstPageObject + index * 2)
                    .append(" 0 R ");
        }
        objects.add(ascii("<< /Type /Pages /Count "
                + pages.size() + " /Kids [ " + kids + "] >>"));
        objects.add(ascii("""
                << /Type /Font /Subtype /Type0
                   /BaseFont /HYSMyeongJo-Medium
                   /Encoding /UniKS-UCS2-H
                   /DescendantFonts [4 0 R] >>
                """));
        objects.add(ascii("""
                << /Type /Font /Subtype /CIDFontType0
                   /BaseFont /HYSMyeongJo-Medium
                   /CIDSystemInfo
                     << /Registry (Adobe)
                        /Ordering (Korea1)
                        /Supplement 2 >>
                   /DW 1000 >>
                """));

        for (int index = 0; index < pages.size(); index++) {
            int pageObject = firstPageObject + index * 2;
            int contentObject = pageObject + 1;
            objects.add(ascii("<< /Type /Page /Parent 2 0 R "
                    + "/MediaBox [0 0 595 842] "
                    + "/Resources << /Font << /F0 "
                    + fontObject + " 0 R >> >> "
                    + "/Contents " + contentObject + " 0 R >>"));
            byte[] content = pageContent(pages.get(index));
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            write(stream, "<< /Length " + content.length
                    + " >>\nstream\n");
            stream.writeBytes(content);
            write(stream, "\nendstream");
            objects.add(stream.toByteArray());
        }

        return assemble(objects);
    }

    private static byte[] pageContent(List<String> lines) {
        StringBuilder content = new StringBuilder();
        content.append("BT\n/F0 10 Tf\n14 TL\n50 790 Td\n");
        for (String line : lines) {
            content.append('<')
                    .append(utf16Hex(line))
                    .append("> Tj\nT*\n");
        }
        content.append("ET");
        return ascii(content.toString());
    }

    private static String utf16Hex(String value) {
        StringBuilder hex = new StringBuilder(value.length() * 4);
        for (int index = 0; index < value.length(); index++) {
            hex.append(String.format(
                    Locale.ROOT,
                    "%04X",
                    (int) value.charAt(index)));
        }
        return hex.toString();
    }

    private static byte[] assemble(List<byte[]> objects) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write("%PDF-1.7\n%".getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[] {
                    (byte) 0xE2,
                    (byte) 0xE3,
                    (byte) 0xCF,
                    (byte) 0xD3
            });
            output.write('\n');

            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);
            for (int object = 1; object < objects.size(); object++) {
                offsets.add(output.size());
                write(output, object + " 0 obj\n");
                output.write(objects.get(object));
                write(output, "\nendobj\n");
            }

            int xrefOffset = output.size();
            write(output, "xref\n0 " + objects.size() + "\n");
            write(output, "0000000000 65535 f \n");
            for (int object = 1; object < objects.size(); object++) {
                write(output, String.format(
                        Locale.ROOT,
                        "%010d 00000 n \n",
                        offsets.get(object)));
            }
            write(output, "trailer\n<< /Size " + objects.size()
                    + " /Root 1 0 R >>\nstartxref\n"
                    + xrefOffset + "\n%%EOF\n");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "In-memory PDF rendering failed",
                    exception);
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void write(
            ByteArrayOutputStream output,
            String value
    ) {
        output.writeBytes(ascii(value));
    }
}
