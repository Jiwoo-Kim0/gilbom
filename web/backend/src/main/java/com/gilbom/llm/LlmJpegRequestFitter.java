package com.gilbom.llm;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * LLM 전송 직전에만 JPEG를 메모리에서 축소·재압축한다.
 *
 * <p>원본 객체와 저장소 파일은 변경하지 않는다. 디코딩 크기와 반복 횟수를 제한하고,
 * 목표 크기에 안전하게 맞출 수 없으면 빈 결과를 돌려 호출부가 텍스트 요청으로
 * 전환하게 한다.
 */
final class LlmJpegRequestFitter {

    private static final long MAXIMUM_PIXELS = 16_777_216L;
    private static final int MINIMUM_JPEG_BYTES = 4_096;
    private static final float MINIMUM_QUALITY = 0.55F;
    private static final float MAXIMUM_QUALITY = 0.90F;
    private static final int QUALITY_SEARCH_STEPS = 7;
    private static final double[] SCALE_STEPS = {
            1.0D,
            0.85D,
            0.70D,
            0.55D,
            0.40D,
            0.30D
    };

    Optional<LlmImageContent> fit(
            LlmImageContent source,
            int maximumJpegBytes
    ) {
        if (source == null
                || maximumJpegBytes < MINIMUM_JPEG_BYTES) {
            return Optional.empty();
        }
        BufferedImage decoded = decode(source);
        if (decoded == null) {
            return Optional.empty();
        }
        for (double scale : SCALE_STEPS) {
            int width = Math.max(
                    1,
                    (int) Math.round(decoded.getWidth() * scale));
            int height = Math.max(
                    1,
                    (int) Math.round(decoded.getHeight() * scale));
            BufferedImage candidate = scale == 1.0D
                    ? toRgb(decoded)
                    : resize(decoded, width, height);
            byte[] encoded = highestQualityWithin(
                    candidate,
                    maximumJpegBytes);
            if (encoded != null) {
                return Optional.of(new LlmImageContent(
                        source.defectId(),
                        source.incidentCode(),
                        encoded,
                        source.contentType(),
                        width,
                        height));
            }
        }
        return Optional.empty();
    }

    private static BufferedImage decode(LlmImageContent source) {
        long declaredPixels = (long) source.width()
                * source.height();
        if (declaredPixels < 1
                || declaredPixels > MAXIMUM_PIXELS) {
            return null;
        }
        Iterator<ImageReader> readers =
                ImageIO.getImageReadersByFormatName("jpeg");
        if (!readers.hasNext()) {
            return null;
        }
        ImageReader reader = readers.next();
        try (ByteArrayInputStream bytes =
                        new ByteArrayInputStream(source.bytes());
                MemoryCacheImageInputStream input =
                        new MemoryCacheImageInputStream(bytes)) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width != source.width()
                    || height != source.height()
                    || (long) width * height > MAXIMUM_PIXELS) {
                return null;
            }
            BufferedImage image = reader.read(0);
            if (image == null) {
                return null;
            }
            return image;
        } catch (IOException | RuntimeException exception) {
            return null;
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage resize(
            BufferedImage source,
            int width,
            int height
    ) {
        BufferedImage target = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(
                    source,
                    0,
                    0,
                    width,
                    height,
                    null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        return resize(source, source.getWidth(), source.getHeight());
    }

    private static byte[] highestQualityWithin(
            BufferedImage image,
            int maximumJpegBytes
    ) {
        byte[] minimum = encode(image, MINIMUM_QUALITY);
        if (minimum == null || minimum.length > maximumJpegBytes) {
            return null;
        }
        byte[] best = minimum;
        float low = MINIMUM_QUALITY;
        float high = MAXIMUM_QUALITY;
        for (int step = 0;
                step < QUALITY_SEARCH_STEPS;
                step++) {
            float quality = (low + high) / 2.0F;
            byte[] encoded = encode(image, quality);
            if (encoded != null
                    && encoded.length <= maximumJpegBytes) {
                best = encoded;
                low = quality;
            } else {
                high = quality;
            }
        }
        return best;
    }

    private static byte[] encode(
            BufferedImage image,
            float quality
    ) {
        Iterator<ImageWriter> writers =
                ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output =
                        new ByteArrayOutputStream();
                MemoryCacheImageOutputStream imageOutput =
                        new MemoryCacheImageOutputStream(output)) {
            ImageWriteParam parameters =
                    writer.getDefaultWriteParam();
            if (!parameters.canWriteCompressed()) {
                return null;
            }
            parameters.setCompressionMode(
                    ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.setOutput(imageOutput);
            writer.write(
                    null,
                    new IIOImage(image, null, null),
                    parameters);
            imageOutput.flush();
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            return null;
        } finally {
            writer.dispose();
        }
    }
}
