package cn.edu.ahu.autologin;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

final class WifiTransport implements PortalProtocol.Transport {
    interface Guard { boolean allowed(); }
    private final Network network;
    private final Guard guard;
    WifiTransport(Network network, Guard guard) { this.network = network; this.guard = guard; }
    @Override public String get(String url, Map<String, String> params) throws PortalProtocol.Failure {
        PortalProtocol.checkUrl(url);
        if (Thread.currentThread().isInterrupted() || !guard.allowed())
            throw PortalProtocol.temporary("Wi-Fi 已变化或认证已关闭，已停止请求。");
        if (!params.isEmpty()) url += (url.contains("?") ? "&" : "?") + PortalProtocol.query(params);
        HttpURLConnection connection = null;
        try {
            // Per-request Wi-Fi binding also works when mobile data remains the default network.
            connection = (HttpURLConnection) network.openConnection(new URL(url), Proxy.NO_PROXY);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AHUAutoLogin/1.0");
            connection.setRequestProperty("Referer", PortalProtocol.PORTAL);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            int code = connection.getResponseCode();
            if (code != 200) throw PortalProtocol.temporary("认证服务器返回 HTTP " + code + "，稍后重试。");
            try (InputStream source = connection.getInputStream();
                 PushbackInputStream probe = new PushbackInputStream(source, 2)) {
                int first = probe.read(), second = probe.read();
                if (second != -1) probe.unread(second);
                if (first != -1) probe.unread(first);
                InputStream input = first == 0x1f && second == 0x8b ? new GZIPInputStream(probe) : probe;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw PortalProtocol.temporary("认证服务器返回的数据过大。");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    output.write(buffer, 0, count);
                }
                return decode(output.toByteArray(), connection.getContentType());
            }
        } catch (IOException | RuntimeException failure) {
            // Never display the exception or URL: a GET authentication request contains credentials.
            throw PortalProtocol.temporary("暂时无法访问校园网认证服务器，请确认已连接校园 Wi-Fi。");
        } finally { if (connection != null) connection.disconnect(); }
    }
    private static String decode(byte[] bytes, String contentType) throws PortalProtocol.Failure {
        List<String> encodings = new ArrayList<>();
        Matcher matcher = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE)
                .matcher(contentType == null ? "" : contentType);
        if (matcher.find()) encodings.add(matcher.group(1));
        encodings.add("UTF-8"); encodings.add("GB18030");
        for (String name : encodings) {
            try { return Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException | IllegalArgumentException ignored) { }
        }
        throw PortalProtocol.temporary("认证服务器返回了无法识别的文本编码。");
    }
}
