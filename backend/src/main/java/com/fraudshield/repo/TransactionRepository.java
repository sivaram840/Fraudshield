package com.fraudshield.repo;

import com.fraudshield.domain.Transaction;
import com.fraudshield.domain.TxnStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByIdempotencyKey(String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> lockById(Long id);

    @Query("select t.fromAccount.id from Transaction t where t.id = :id")
    Optional<Long> fromAccountId(Long id);

    @Query("select t.toAccount.id from Transaction t where t.id = :id")
    Optional<Long> toAccountId(Long id);

    @Query("select t from Transaction t where t.status = com.fraudshield.domain.TxnStatus.COMPLETED "
            + "and t.executedAt >= :since order by t.executedAt")
    List<Transaction> completedSince(Instant since);

    @Query("select t from Transaction t where t.status = com.fraudshield.domain.TxnStatus.COMPLETED "
            + "and t.executedAt >= :since and (t.fromAccount.id in :ids or t.toAccount.id in :ids) order by t.executedAt")
    List<Transaction> completedInvolving(Collection<Long> ids, Instant since);

    @Query("select t from Transaction t where t.status = com.fraudshield.domain.TxnStatus.COMPLETED "
            + "and t.toAccount.id = :accountId and t.executedAt >= :since order by t.executedAt")
    List<Transaction> completedIncoming(Long accountId, Instant since);

    @Query("select t from Transaction t where t.status = com.fraudshield.domain.TxnStatus.COMPLETED "
            + "and t.fromAccount.id = :accountId and t.executedAt >= :since order by t.executedAt")
    List<Transaction> completedOutgoing(Long accountId, Instant since);

    @Query("select count(t) from Transaction t where t.status = com.fraudshield.domain.TxnStatus.COMPLETED "
            + "and t.fromAccount.id = :fromId and t.toAccount.id = :toId")
    long completedBetween(Long fromId, Long toId);

    @Query("select count(t) from Transaction t where t.status = com.fraudshield.domain.TxnStatus.COMPLETED "
            + "and (t.fromAccount.id = :accountId or t.toAccount.id = :accountId) and t.executedAt < :before")
    long completedBefore(Long accountId, Instant before);

    @Query("select t from Transaction t where t.createdAt >= :since order by t.createdAt")
    List<Transaction> createdSince(Instant since);

    @Query("select t from Transaction t where t.fromAccount.id = :accountId or t.toAccount.id = :accountId "
            + "order by t.createdAt desc")
    Page<Transaction> forAccount(Long accountId, Pageable page);

    Page<Transaction> findByStatus(TxnStatus status, Pageable page);

    List<Transaction> findByStatusAndHoldExpiresAtBefore(TxnStatus status, Instant now);

    long countByStatus(TxnStatus status);

    long countByStatusIn(Collection<TxnStatus> statuses);
}
