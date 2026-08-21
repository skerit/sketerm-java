package be.elevenways.sketerm.api;

/**
 * Wire-token lookup shared by every vocabulary enum, derived from the members themselves.
 */
public final class WireValues {

    private WireValues() {
    }

    /**
     * @return the member whose {@link WireValue#wire()} equals the token, or null when unknown
     */
    public static <E extends Enum<E> & WireValue> E parse(Class<E> type, String wire) {

        if (wire == null) {
            return null;
        }

        for (E member : type.getEnumConstants()) {
            if (member.wire().equals(wire)) {
                return member;
            }
        }

        return null;
    }

    /**
     * @throws SketermApiException when the token names no member, so an unknown value fails closed
     */
    public static <E extends Enum<E> & WireValue> E require(Class<E> type, String wire) {

        E member = parse(type, wire);

        if (member == null) {
            throw new SketermApiException("The server sent '" + wire + "', which is not a known "
                    + type.getSimpleName() + " value");
        }

        return member;
    }
}
