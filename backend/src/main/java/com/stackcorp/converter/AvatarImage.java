package com.stackcorp.converter;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * 올린 아바타 사진을 다시 만든다(ADR-0038). 신뢰할 수 없는 이미지를 해석하는 코드는 이 process에만 있다.
 * <ul>
 * <li>앞부분 signature로 PNG나 JPEG만 받고, 그 형식의 reader 하나로만 읽는다. 확장자나 Content-Type은 보지 않는다.</li>
 * <li>픽셀을 풀기 전에 header의 가로·세로를 보고 상한을 넘으면 거부한다(압축 폭탄). 메타데이터는 읽지 않는다.</li>
 * <li>가운데 정사각형을 {@value #SIZE}px로 줄여 흰 배경 위에 그리고 JPEG로 다시 인코딩한다. 원본 바이트와 메타데이터는 결과에
 * 없다.</li>
 * </ul>
 */
final class AvatarImage {

	static final int SIZE = 256;

	/** 받는 원본 크기 상한. api가 먼저 같은 상한을 본다. */
	static final int MAX_BYTES = 5 << 20;

	static final int MAX_SIDE = 4096;

	static final long MAX_PIXELS = 16L << 20;

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private AvatarImage() {
	}

	/** @return {@value #SIZE}px 정사각형 JPEG */
	static byte[] normalize(byte[] bytes) throws ConversionFailure {
		if (bytes.length > MAX_BYTES) {
			throw new ConversionFailure("too_large", "이미지가 크기 상한을 넘습니다.");
		}
		String format = startsWith(bytes, PNG) ? "png" : startsWith(bytes, JPEG) ? "jpeg" : null;
		if (format == null) {
			throw new ConversionFailure("not_image", "PNG나 JPEG 이미지가 아닙니다.");
		}
		ImageIO.setUseCache(false);
		BufferedImage source;
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
			if (in == null || !readers.hasNext()) {
				throw new ConversionFailure("not_image", "이미지를 읽을 수 없습니다.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1) {
					throw new ConversionFailure("not_image", "이미지 크기가 올바르지 않습니다.");
				}
				if (width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS) {
					throw new ConversionFailure("too_large", "이미지의 가로·세로가 상한을 넘습니다.");
				}
				int side = Math.min(width, height);
				ImageReadParam param = reader.getDefaultReadParam();
				param.setSourceRegion(new Rectangle((width - side) / 2, (height - side) / 2, side, side));
				// 결과의 두 배 이상을 남기고 줄여 읽는다. 큰 사진도 메모리에 풀어 놓는 픽셀이 적다.
				int step = Math.max(1, side / (SIZE * 2));
				param.setSourceSubsampling(step, step, 0, 0);
				source = reader.read(0, param);
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			throw new ConversionFailure("not_image", "이미지를 읽을 수 없습니다.");
		}
		return jpeg(scaled(source));
	}

	private static BufferedImage scaled(BufferedImage source) {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, SIZE, SIZE);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, SIZE, SIZE, null);
		}
		finally {
			g.dispose();
		}
		return out;
	}

	private static byte[] jpeg(BufferedImage image) throws ConversionFailure {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.9f);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		catch (IOException ex) {
			throw new ConversionFailure("failed", "이미지를 인코딩하지 못했습니다.");
		}
		finally {
			writer.dispose();
		}
		return buffer.toByteArray();
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
	}

}
