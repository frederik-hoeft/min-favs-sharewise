package de.hsh.favs.sharewise.shared.infra.persistence.jpa.internal;

import de.hsh.favs.sharewise.shared.infra.transactions.TransactionSegmentAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Guards the JPA facade and query handles issued for one transaction segment.
 * Spring's shared EntityManager routes to the current persistence context; the
 * lease prevents an old facade or query from following a later transaction.
 */
final class SegmentBoundJpaHandles {

    private SegmentBoundJpaHandles() { }

    static EntityManager guard(EntityManager delegate, TransactionSegmentAccess.SegmentLease lease) {
        Objects.requireNonNull(delegate);
        Objects.requireNonNull(lease);
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
            new Class<?>[]{EntityManager.class}, new Guard(delegate, lease));
    }

    private static final class Guard implements InvocationHandler {
        private final Object delegate;
        private final TransactionSegmentAccess.SegmentLease lease;

        Guard(Object delegate, TransactionSegmentAccess.SegmentLease lease) {
            this.delegate = delegate;
            this.lease = lease;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Segment-bound " + (delegate instanceof EntityManager ? "EntityManager" : "Query");
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }

            lease.requireCurrent();
            String name = method.getName();
            // Do not expose the underlying Spring proxy, Hibernate Session or
            // persistence factory through APIs that bypass the segment guard.
            if (name.equals("unwrap") || name.equals("getDelegate")
                    || name.equals("getEntityManagerFactory") || name.equals("getTransaction")
                    || name.equals("close") || name.equals("getResultStream")) {
                throw new UnsupportedOperationException("Direct resource access is not supported: " + name);
            }

            try {
                Object result = method.invoke(delegate, args);
                if (result == delegate) {
                    // Query fluent mutators return their own receiver; preserve
                    // the guarded view rather than leaking the unguarded query.
                    return proxy;
                }
                if (result instanceof Query query) {
                    Class<?> returnedType = method.getReturnType();
                    if (!returnedType.isInterface() || !Query.class.isAssignableFrom(returnedType)) {
                        throw new IllegalStateException("Cannot guard a JPA query return type: " + returnedType);
                    }
                    return Proxy.newProxyInstance(Query.class.getClassLoader(),
                        new Class<?>[]{returnedType}, new Guard(query, lease));
                }
                return result;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
