package edu.ouc.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公共接口单元测试：本地二维码生成 + 文件上传白名单 + 文件下载（路径穿越/缓存/304）
 */
class CommonControllerTest {

    private CommonController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        controller = new CommonController();
        ReflectionTestUtils.setField(controller, "basePath", tempDir.toString());
    }

    // ------------------------------------------------------------ 支付二维码

    @Test
    @DisplayName("二维码：正常内容返回 data:image/png;base64")
    void payQr_ok() {
        R<String> r = controller.payQr("order-20261017-001");
        assertEquals(1, r.getCode(), "应返回成功 code=1");
        assertNotNull(r.getData());
        assertTrue(r.getData().startsWith("data:image/png;base64,"), "必须是 PNG 的 data URL");
        assertTrue(r.getData().length() > 200, "二维码数据不应为空");
    }

    @Test
    @DisplayName("二维码：null / 空串 / 纯空格均被拒绝")
    void payQr_blankRejected() {
        assertNotEquals(1, controller.payQr(null).getCode());
        assertNotEquals(1, controller.payQr("").getCode());
        assertNotEquals(1, controller.payQr("   ").getCode());
    }

    @Test
    @DisplayName("二维码：超过 512 字符被拒绝（防止超大文本拖垮生成）")
    void payQr_tooLongRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 513; i++) sb.append('x');
        R<String> r = controller.payQr(sb.toString());
        assertNotEquals(1, r.getCode());
        assertEquals("二维码内容过长", r.getMsg());
    }

    @Test
    @DisplayName("二维码：512 字符边界内可正常生成")
    void payQr_boundary512() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 512; i++) sb.append('x');
        assertEquals(1, controller.payQr(sb.toString()).getCode());
    }

    // ------------------------------------------------------------ 文件上传

    @Test
    @DisplayName("上传：允许的图片扩展名可正常落盘")
    void upload_imageOk() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "dish.jpg", "image/jpeg", new byte[]{1, 2, 3});
        R<String> r = controller.upload(file);
        assertEquals(1, r.getCode(), r.getMsg());
        assertTrue(r.getData().endsWith(".jpg"));
        assertTrue(new File(tempDir.toFile(), r.getData()).exists(), "文件应已写入 basePath");
    }

    @Test
    @DisplayName("上传：.jsp/.exe 等危险扩展名必须被拒绝")
    void upload_dangerousExtensionRejected() {
        MockMultipartFile jsp = new MockMultipartFile("file", "shell.jsp", "text/plain", new byte[]{1});
        assertNotEquals(1, controller.upload(jsp).getCode());

        MockMultipartFile exe = new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[]{1});
        assertNotEquals(1, controller.upload(exe).getCode());
    }

    @Test
    @DisplayName("上传：无扩展名 / 空文件名必须被拒绝")
    void upload_invalidNameRejected() {
        assertNotEquals(1, controller.upload(new MockMultipartFile("file", "noext", "image/jpeg", new byte[]{1})).getCode());
        assertNotEquals(1, controller.upload(new MockMultipartFile("file", "", "image/jpeg", new byte[]{1})).getCode());
        assertNotEquals(1, controller.upload(new MockMultipartFile("file", "abc.", "image/jpeg", new byte[]{1})).getCode());
    }

    // ------------------------------------------------------------ 文件下载

    @Test
    @DisplayName("下载：路径穿越 ../../etc/passwd 必须返回 403")
    void download_pathTraversalForbidden() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.download("../../etc/passwd", resp, new MockHttpServletRequest());
        assertEquals(403, resp.getStatus());
    }

    @Test
    @DisplayName("下载：空文件名返回 400")
    void download_blankNameBadRequest() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.download("  ", resp, new MockHttpServletRequest());
        assertEquals(400, resp.getStatus());
    }

    @Test
    @DisplayName("下载：文件不存在返回 404")
    void download_notFound() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.download("not-exist.jpg", resp, new MockHttpServletRequest());
        assertEquals(404, resp.getStatus());
    }

    @Test
    @DisplayName("下载：正常文件返回 200，并写入强缓存头与 ETag")
    void download_okWithCacheHeaders() throws Exception {
        byte[] body = new byte[]{1, 2, 3, 4, 5};
        Files.write(tempDir.resolve("pic.jpg"), body);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.download("pic.jpg", resp, new MockHttpServletRequest());

        assertEquals(200, resp.getStatus());
        assertEquals("image/jpeg", resp.getContentType());
        assertNotNull(resp.getHeader("ETag"), "必须下发 ETag 才能命中 304");
        assertTrue(String.valueOf(resp.getHeader("Cache-Control")).contains("immutable"));
        assertArrayEquals(body, resp.getContentAsByteArray());
    }

    @Test
    @DisplayName("下载：If-None-Match 命中 ETag 时返回 304 且不传文件体")
    void download_notModified304() throws Exception {
        byte[] body = new byte[]{9, 8, 7};
        Files.write(tempDir.resolve("pic2.png"), body);

        // 第一次请求拿到 ETag
        MockHttpServletResponse first = new MockHttpServletResponse();
        controller.download("pic2.png", first, new MockHttpServletRequest());
        String etag = first.getHeader("ETag");
        assertNotNull(etag);

        // 带 If-None-Match 再请求
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("If-None-Match", etag);
        MockHttpServletResponse second = new MockHttpServletResponse();
        controller.download("pic2.png", second, req);

        assertEquals(304, second.getStatus(), "命中缓存应返回 304");
        assertEquals(0, second.getContentAsByteArray().length, "304 不应带文件体");
    }
}
