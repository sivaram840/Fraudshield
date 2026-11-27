package com.fraudshield.repo;

import com.fraudshield.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByRoleAscUsernameAsc();

    Optional<AppUser> findFirstByAccountId(Long accountId);
}
