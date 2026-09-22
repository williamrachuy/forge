package forge.gamemodes.net.event;

public class LoginEvent implements NetEvent {
    private static final long serialVersionUID = -8865183377417377938L;

    private final String username;
    private final int avatarIndex, sleeveIndex;
    private final String version;
    private final boolean libgdx;
    // Added after the class shipped; serialVersionUID is unchanged on purpose so a login from a
    // build without this field still deserializes (with null) and can be refused with a message.
    private final String wireFingerprint;
    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex, final String version,
                      final boolean libgdx, final String wireFingerprint) {
        this.username = username;
        this.avatarIndex = avatarIndex;
        this.sleeveIndex = sleeveIndex;
        this.version = version;
        this.libgdx = libgdx;
        this.wireFingerprint = wireFingerprint;
    }

    public String getUsername() {
        return username;
    }

    public int getAvatarIndex() {
        return avatarIndex;
    }

    public int getSleeveIndex() {
        return sleeveIndex;
    }

    public String getVersion() {
        return version;
    }

    public boolean isLibgdx() {
        return libgdx;
    }

    /** @see forge.gamemodes.net.WireFingerprint */
    public String getWireFingerprint() {
        return wireFingerprint;
    }
}
