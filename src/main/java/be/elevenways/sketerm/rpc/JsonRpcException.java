package be.elevenways.sketerm.rpc;

import java.util.Map;

/**
 * A JSON-RPC error object returned in place of a result.
 *
 * <p>Distinct from a tool failure, which arrives as a successful result carrying isError.</p>
 */
public class JsonRpcException extends RuntimeException {

    private final int code;
    private final Object data;

    public JsonRpcException(int code, String message, Object data) {
        super("JSON-RPC error " + code + ": " + message
                + (data == null ? "" : " (data: " + data + ")"));
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return this.code;
    }

    /**
     * @return the raw data member, typically a {@link Map} or null
     */
    public Object getData() {
        return this.data;
    }
}
