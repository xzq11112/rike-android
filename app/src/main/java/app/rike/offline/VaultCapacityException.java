package app.rike.offline;

/** Typed preflight failure: never confused with malformed input. */
final class VaultCapacityException extends IllegalArgumentException {
    final int bytes;
    VaultCapacityException(int bytes){super("Vault capacity exceeded");this.bytes=bytes;}
}
