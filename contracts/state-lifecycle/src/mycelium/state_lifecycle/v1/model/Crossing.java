package mycelium.state_lifecycle.v1.model;

import java.util.Objects;

/**
 * The host or hosts a transition acts on, with the platform's runtime object where one comes into being. Each case goes
 * with the shapes it can carry: {@code Stay} with SAVES and STAYS, {@code Begin} with BEGINS, {@code End} with ENDS,
 * {@code Succeed} with SUCCEEDS, {@code Fork} with FORKS.
 */
public sealed interface Crossing<H> permits Crossing.Stay, Crossing.Begin, Crossing.End, Crossing.Succeed, Crossing.Fork {
    /** A live host, unchanged in identity. */
    record Stay<H>(HostRef host) implements Crossing<H> {
        public Stay {
            Objects.requireNonNull(host, "host");
        }
    }

    /** A host that is not live comes to exist as this object. */
    record Begin<H>(HostId host, H handle) implements Crossing<H> {
        public Begin {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(handle, "handle");
        }
    }

    /** A live host's incarnation ends. */
    record End<H>(HostRef host) implements Crossing<H> {
        public End {
            Objects.requireNonNull(host, "host");
        }
    }

    /** A live host's object is replaced by this one. */
    record Succeed<H>(HostRef host, H handle) implements Crossing<H> {
        public Succeed {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(handle, "handle");
        }
    }

    /** A live host gives rise to a second host, which is not live yet, of the same kind. */
    record Fork<H>(HostRef source, HostId target, H handle) implements Crossing<H> {
        public Fork {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(handle, "handle");
        }
    }
}
