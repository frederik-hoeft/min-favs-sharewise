package de.hsh.favs.sharewise.finance.internal.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.hsh.favs.sharewise.finance.internal.actor.MembershipState;
import de.hsh.favs.sharewise.finance.internal.projection.ActivateFinancialGroup;
import de.hsh.favs.sharewise.finance.internal.projection.ActivationOutcome;
import de.hsh.favs.sharewise.finance.internal.projection.ApplyMembershipChange;
import de.hsh.favs.sharewise.finance.internal.projection.FinanceProjectionService;
import de.hsh.favs.sharewise.finance.internal.projection.MembershipOutcome;
import de.hsh.favs.sharewise.groups.events.GroupCreatedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberAddedV1;
import de.hsh.favs.sharewise.groups.events.GroupMemberRemovedV1;
import de.hsh.favs.sharewise.shared.infra.di.OperationIngressDispatcher;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * DLV-T03: each Group event maps to exactly one Finance projection operation, every outcome
 * returns normally so the publication completes, and every exception propagates unchanged. The
 * real contradiction path through the registry is covered by the delivery E2Es.
 */
class GroupEventListenerTests {

    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-26T12:00:00Z");

    private final FinanceProjectionService projection = mock(FinanceProjectionService.class);
    private final RecordingIngress ingress = new RecordingIngress(projection);
    private final GroupEventListener listener = new GroupEventListener(ingress);

    @Test
    void creationActivatesTheGroupRootInOneDispatch() {
        for (ActivationOutcome outcome : ActivationOutcome.values()) {
            when(projection.activate(new ActivateFinancialGroup(GROUP))).thenReturn(outcome);

            listener.on(new GroupCreatedV1(UUID.randomUUID(), GROUP, AT));
        }

        assertEquals(ActivationOutcome.values().length, ingress.dispatches());
        verify(projection, times(ActivationOutcome.values().length))
            .activate(new ActivateFinancialGroup(GROUP));
        verifyNoMoreInteractions(projection);
    }

    @Test
    void membershipFactsApplyTheirResultingStateAndRevisionInOneDispatch() {
        ApplyMembershipChange added = new ApplyMembershipChange(GROUP, USER, 2, MembershipState.ACTIVE);
        ApplyMembershipChange removed = new ApplyMembershipChange(GROUP, USER, 3, MembershipState.REMOVED);
        for (MembershipOutcome outcome : MembershipOutcome.values()) {
            when(projection.applyMembershipChange(added)).thenReturn(outcome);
            when(projection.applyMembershipChange(removed)).thenReturn(outcome);

            listener.on(new GroupMemberAddedV1(UUID.randomUUID(), GROUP, USER, 2, AT));
            listener.on(new GroupMemberRemovedV1(UUID.randomUUID(), GROUP, USER, 3, AT));
        }

        assertEquals(2 * MembershipOutcome.values().length, ingress.dispatches());
        verify(projection, times(MembershipOutcome.values().length)).applyMembershipChange(added);
        verify(projection, times(MembershipOutcome.values().length)).applyMembershipChange(removed);
        verifyNoMoreInteractions(projection);
    }

    @Test
    void projectionFailuresPropagateUnchanged() {
        IllegalStateException activation = new IllegalStateException("database unavailable");
        IllegalArgumentException added = new IllegalArgumentException("contradicting fact");
        IllegalStateException removed = new IllegalStateException("lock timeout");
        when(projection.activate(new ActivateFinancialGroup(GROUP))).thenThrow(activation);
        when(projection.applyMembershipChange(new ApplyMembershipChange(GROUP, USER, 2, MembershipState.ACTIVE)))
            .thenThrow(added);
        when(projection.applyMembershipChange(new ApplyMembershipChange(GROUP, USER, 3, MembershipState.REMOVED)))
            .thenThrow(removed);

        assertSame(activation, assertThrows(IllegalStateException.class,
            () -> listener.on(new GroupCreatedV1(UUID.randomUUID(), GROUP, AT))));
        assertSame(added, assertThrows(IllegalArgumentException.class,
            () -> listener.on(new GroupMemberAddedV1(UUID.randomUUID(), GROUP, USER, 2, AT))));
        assertSame(removed, assertThrows(IllegalStateException.class,
            () -> listener.on(new GroupMemberRemovedV1(UUID.randomUUID(), GROUP, USER, 3, AT))));
        assertEquals(3, ingress.dispatches());
    }

    @Test
    void everyHandlerIsAnAsyncTransactionalListenerWithItsPersistedId() {
        Map<Class<?>, String> ids = Arrays.stream(GroupEventListener.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(TransactionalEventListener.class))
            .peek(method -> assertTrue(method.isAnnotationPresent(Async.class), method.getName()))
            .collect(Collectors.toMap(method -> method.getParameterTypes()[0],
                GroupEventListenerTests::listenerId));

        assertEquals(Map.of(
            GroupCreatedV1.class, "finance.group-activation.v1",
            GroupMemberAddedV1.class, "finance.membership-added.v1",
            GroupMemberRemovedV1.class, "finance.membership-removed.v1"), ids);
    }

    private static String listenerId(Method method) {
        return method.getAnnotation(TransactionalEventListener.class).id();
    }

    /** Applies each dispatched action to the projection, as a fresh operation scope would. */
    private static final class RecordingIngress implements OperationIngressDispatcher {
        private final FinanceProjectionService projection;
        private final List<Class<?>> entries = new ArrayList<>();

        RecordingIngress(FinanceProjectionService projection) {
            this.projection = projection;
        }

        @Override
        public <S, R> R dispatch(Class<S> entryType, Function<S, R> action) {
            entries.add(entryType);
            assertSame(FinanceProjectionService.class, entryType);
            return action.apply(entryType.cast(projection));
        }

        int dispatches() {
            return entries.size();
        }
    }
}
