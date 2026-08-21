package be.elevenways.sketerm.api;

/**
 * The server declined on policy grounds, not because of a fault.
 *
 * <p>This is what an exhausted network policy answers with: past a latched budget
 * {@link Page#navigate}, {@link Page#act}, {@link Page#evaluate} and a load wait are all refused,
 * permanently and non-retryably, while read tools keep working. The sentence carries the numbers,
 * but {@link Page#policy()} is the machine-readable accounting - which budget went, how many
 * requests and bytes it took - and {@link Page#isPolicyExhausted()} is the cached fact.</p>
 */
public class RefusedException extends SketermApiException {

    RefusedException(String message, String toolName, Boolean retryable, Throwable cause) {
        super(message, toolName, ErrorCode.REFUSED, ErrorCode.REFUSED.wire(), retryable, cause);
    }
}
