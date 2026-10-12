package mycelium.state_lifecycle.v1.model;

/** What a host is. A slot's scope is a kind: the slot lives on hosts of that kind and no other. */
public enum HostKind {
    PLAYER,
    ENTITY,
    LEVEL,
    SERVER
}
