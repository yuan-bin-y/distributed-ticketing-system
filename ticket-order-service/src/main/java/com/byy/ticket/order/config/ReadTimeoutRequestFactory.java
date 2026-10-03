package com.byy.ticket.order.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 包装 JDK 请求工厂，保留可以被业务 Client 识别的正文读取超时异常。
 * 真正的读取超时由底层请求工厂控制；本类根据耗时识别因超时关闭响应流产生的异常。
 */
final class ReadTimeoutRequestFactory implements ClientHttpRequestFactory {
    private final ClientHttpRequestFactory delegate;
    private final long readTimeoutNanos;

    /**
     * 保存底层请求工厂，并把读取超时转换为纳秒，供异常分类时比较。
     */
    ReadTimeoutRequestFactory(ClientHttpRequestFactory delegate, Duration readTimeout) {
        this.delegate = delegate;
        this.readTimeoutNanos = readTimeout.toNanos();
    }

    /**
     * 创建底层 HTTP 请求并包装请求、响应；在实际执行 HTTP 时开始计时。
     * 请求头、地址和输出流转交底层对象，响应正文由 timedBody 包装。
     */
    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        ClientHttpRequest request = delegate.createRequest(uri, method);
        return new ClientHttpRequest() {
            /**
             * 返回底层请求的 HTTP 方法。
             */
            @Override
            public HttpMethod getMethod() { return request.getMethod(); }

            /**
             * 返回底层请求的目标 URI。
             */
            @Override
            public URI getURI() { return request.getURI(); }

            /**
             * 转交请求属性，保留底层客户端和拦截器所需的上下文。
             */
            @Override
            public Map<String, Object> getAttributes() { return request.getAttributes(); }

            /**
             * 返回底层请求头，供调用方添加 traceId、内容类型等信息。
             */
            @Override
            public HttpHeaders getHeaders() { return request.getHeaders(); }

            /**
             * 返回底层请求输出流，JSON 转换器通过它写入请求正文。
             */
            @Override
            public OutputStream getBody() throws IOException { return request.getBody(); }

            /**
             * 执行已经解析出实例地址的 HTTP 请求，记录开始时间，再包装响应正文。
             * 服务发现耗时不计入此处的 HTTP 正文读取耗时。
             */
            @Override
            public ClientHttpResponse execute() throws IOException {
                // 到这里 URI 已由 LoadBalancer 解析；不把服务发现耗时计入 HTTP 读取时间。
                long started = System.nanoTime();
                ClientHttpResponse response = request.execute();
                InputStream body = timedBody(response.getBody(), started);
                return new ClientHttpResponse() {
                    /**
                     * 返回底层响应的 HTTP 状态码，供 Client 判断成功或失败。
                     */
                    @Override
                    public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }

                    /**
                     * 返回底层响应的状态说明。
                     */
                    @Override
                    public String getStatusText() throws IOException { return response.getStatusText(); }

                    /**
                     * 返回底层响应头，保留上游 HTTP 响应信息。
                     */
                    @Override
                    public HttpHeaders getHeaders() { return response.getHeaders(); }

                    /**
                     * 返回包装后的响应输入流，读取发生异常时可以识别是否属于超时。
                     */
                    @Override
                    public InputStream getBody() { return body; }

                    /**
                     * 关闭底层响应，释放相关资源。
                     */
                    @Override
                    public void close() { response.close(); }
                };
            }
        };
    }

    /**
     * 包装响应输入流；单字节和批量读取发生 IOException 时交给 classify 判断。
     */
    private InputStream timedBody(InputStream body, long started) {
        return new FilterInputStream(body) {
            /**
             * 读取一个字节；失败时保留或转换异常，使上层能够识别读取超时。
             */
            @Override
            public int read() throws IOException {
                try {
                    return super.read();
                } catch (IOException exception) {
                    throw classify(exception, started);
                }
            }

            /**
             * 读取指定范围的多个字节；失败时使用同样的超时分类逻辑。
             */
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    return super.read(bytes, offset, length);
                } catch (IOException exception) {
                    throw classify(exception, started);
                }
            }
        };
    }

    /**
     * 已经是 SocketTimeoutException 时直接保留；否则耗时达到读取超时后包装成超时异常。
     * 原异常作为 cause 保存；未达到超时的普通 I/O 异常原样返回。
     */
    private IOException classify(IOException exception, long started) {
        if (!(exception instanceof SocketTimeoutException)
                && System.nanoTime() - started >= readTimeoutNanos) {
            SocketTimeoutException timeout = new SocketTimeoutException("HTTP response body read timed out");
            timeout.initCause(exception);
            return timeout;
        }
        return exception;
    }
}
