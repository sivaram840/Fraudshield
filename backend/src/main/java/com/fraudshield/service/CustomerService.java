package com.fraudshield.service;

import com.fraudshield.domain.*;
import com.fraudshield.repo.AccountRepository;
import com.fraudshield.repo.AlertRepository;
import com.fraudshield.repo.TransactionRepository;
import com.fraudshield.web.ApiException;
import com.fraudshield.web.dto.Dtos.*;
import com.fraudshield.web.dto.PortalDtos.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

/**
 * The customer's view of the bank: only their own account, plain-language statuses
 * and no model internals.
 */
@Service
public class CustomerService {
    private static final EnumSet<AlertType> CUSTOMER_VISIBLE = EnumSet.of(AlertType.MULE_WARNING, AlertType.MFA_FAILED);

    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final AlertRepository alerts;
    private final TransactionService transactions;
    private final CaseService cases;
    private final AlertService alertService;
    private final AuditService audit;

    public CustomerService(AccountRepository accounts, TransactionRepository txns, AlertRepository alerts,
                           TransactionService transactions, CaseService cases, AlertService alertService,
                           AuditService audit) {
        this.accounts = accounts;
        this.txns = txns;
        this.alerts = alerts;
        this.transactions = transactions;
        this.cases = cases;
        this.alertService = alertService;
        this.audit = audit;
    }

    private Account own(AppUser user) {
        return accounts.findById(user.getAccount().getId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public CustomerOverview overview(AppUser user) {
        Account a = own(user);
        List<CustomerTxn> recent = history(user, 10);
        long pending = recent.stream().filter(CustomerTxn::canVerifyOtp).count();
        return new CustomerOverview(AccountDto.of(a), warnings(a), recent, pending);
    }

    private List<AlertDto> warnings(Account a) {
        return alerts.findByAccountIdOrderByCreatedAtDesc(a.getId()).stream()
                .filter(x -> x.getStatus() == AlertStatus.OPEN && CUSTOMER_VISIBLE.contains(x.getType()))
                .map(AlertDto::of).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerTxn> history(AppUser user, int size) {
        Account a = own(user);
        return txns.forAccount(a.getId(), PageRequest.of(0, size)).getContent().stream()
                // incoming transfers are shown only once they actually arrived
                .filter(t -> t.getFromAccount().getId().equals(a.getId()) || t.getStatus() == TxnStatus.COMPLETED)
                .map(t -> view(t, a)).toList();
    }

    @Transactional(readOnly = true)
    public Payee payee(String accountNumber) {
        Account a = accounts.findByAccountNumber(accountNumber.trim())
                .orElseThrow(() -> ApiException.notFound("Account " + accountNumber));
        return new Payee(a.getAccountNumber(), mask(a.getHolderName()), a.getState());
    }

    public CustomerTransferResponse transfer(AppUser user, CustomerTransferRequest req, String key) {
        Account a = own(user);
        if (a.getStatus() == AccountStatus.FROZEN) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "Your account is frozen. Please contact the bank.");
        }
        TransferResponse r = transactions.create(new TransferRequest(a.getAccountNumber(), req.toAccount(),
                req.amount(), req.description(), null), key, Origin.API);
        Transaction t = txns.findById(r.transaction().id()).orElseThrow();
        String msg = switch (t.getStatus()) {
            case COMPLETED -> "Money sent.";
            case PENDING_MFA -> "For your security, enter the OTP sent to your registered mobile number.";
            case HELD -> "This transfer is on hold while our fraud team checks it. You will not be charged unless it is approved.";
            case BLOCKED -> "This transfer was blocked to protect you. If someone asked you to move money for them, "
                    + "do not try again and report it below.";
            default -> r.message();
        };
        return new CustomerTransferResponse(view(t, a), r.duplicate(), r.demoOtp(), r.duplicate() ? r.message() : msg);
    }

    public CustomerOtpResult verifyOtp(AppUser user, Long txnId, String code) {
        Account a = own(user);
        mine(txnId, a);
        OtpResult r = transactions.verifyOtp(txnId, code);
        return new CustomerOtpResult(view(txns.findById(txnId).orElseThrow(), a), r.verified(), r.attemptsRemaining(),
                r.message());
    }

    public CustomerTransferResponse resendOtp(AppUser user, Long txnId) {
        Account a = own(user);
        mine(txnId, a);
        TransferResponse r = transactions.resendOtp(txnId);
        return new CustomerTransferResponse(view(txns.findById(txnId).orElseThrow(), a), false, r.demoOtp(),
                "A new OTP was sent.");
    }

    public CustomerTxn cancel(AppUser user, Long txnId) {
        Account a = own(user);
        mine(txnId, a);
        transactions.cancelByCustomer(txnId, user.getUsername());
        return view(txns.findById(txnId).orElseThrow(), a);
    }

    @Transactional
    public CustomerReportResponse report(AppUser user, CustomerReportRequest req) {
        Account a = own(user);
        Transaction t = req.transactionId() == null ? null : txns.findById(req.transactionId())
                .filter(x -> x.getFromAccount().getId().equals(a.getId()) || x.getToAccount().getId().equals(a.getId()))
                .orElseThrow(() -> ApiException.notFound("Transaction " + req.transactionId()));
        String what = switch (req.type()) {
            case UNAUTHORISED_TRANSACTION -> "Customer reports a transaction they did not make (possible account takeover)";
            case ASKED_TO_MOVE_MONEY -> "Customer reports being asked to receive and forward money for a commission "
                    + "(self-reported recruitment: strong indicator of an unwitting mule)";
            case SUSPICIOUS_CREDIT -> "Customer reports an unexpected incoming payment";
            case OTHER -> "Customer report";
        };
        String summary = what + (t == null ? "" : " [" + t.getReference() + "]") + ". Customer says: " + req.details();
        alertService.raise(AlertType.CUSTOMER_REPORT, Severity.HIGH, summary, a, t);
        InvestigationCase c = cases.open(a, what, summary, "CUSTOMER_REPORT",
                req.type() == ReportType.OTHER ? Severity.MEDIUM : Severity.HIGH, user.getUsername());
        audit.log("CUSTOMER_REPORT", "ACCOUNT", a.getId(), user.getUsername(), req.type() + ": " + req.details());
        return new CustomerReportResponse(c.getReference(), "Thank you. Your report " + c.getReference()
                + " was sent to the bank's fraud team and investigators. You can also call the cybercrime helpline 1930.");
    }

    private void mine(Long txnId, Account a) {
        Transaction t = txns.findById(txnId).orElseThrow(() -> ApiException.notFound("Transaction " + txnId));
        if (!t.getFromAccount().getId().equals(a.getId())) {
            throw ApiException.notFound("Transaction " + txnId);
        }
    }

    CustomerTxn view(Transaction t, Account me) {
        boolean out = t.getFromAccount().getId().equals(me.getId());
        Account other = out ? t.getToAccount() : t.getFromAccount();
        String text = switch (t.getStatus()) {
            case COMPLETED -> out ? "Sent" : "Received";
            case PENDING_MFA -> "Waiting for your OTP";
            case HELD -> "On hold for security review";
            case BLOCKED -> "Blocked for your safety";
            case REJECTED -> "Not sent" + (t.getFailureReason() == null ? "" : ": " + t.getFailureReason());
            case EXPIRED -> "Not sent (review window expired)";
            case FAILED -> "Failed" + (t.getFailureReason() == null ? "" : ": " + t.getFailureReason());
            case APPROVED -> "Processing";
        };
        return new CustomerTxn(t.getId(), t.getReference(), out ? "SENT" : "RECEIVED", other.getHolderName(),
                maskNumber(other.getAccountNumber()), t.getAmount(), t.getStatus(), text, t.getDescription(),
                t.getCreatedAt(), out && t.getStatus() == TxnStatus.PENDING_MFA,
                out && (t.getStatus() == TxnStatus.PENDING_MFA || t.getStatus() == TxnStatus.HELD));
    }

    static String mask(String name) {
        StringBuilder b = new StringBuilder();
        for (String part : name.split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            b.append(part.charAt(0)).append("*".repeat(Math.max(0, Math.min(part.length() - 1, 5)))).append(' ');
        }
        return b.toString().trim();
    }

    static String maskNumber(String n) {
        return n.length() <= 4 ? n : "XXXX" + n.substring(n.length() - 4);
    }
}
