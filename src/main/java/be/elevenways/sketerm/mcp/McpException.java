package be.elevenways.sketerm.mcp;

/**
 * Thrown when the MCP handshake or a protocol-level expectation is not met.
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
