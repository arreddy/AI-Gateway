package com.astra.auth.repository;

import com.astra.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByExternalId(UUID externalId);
    Optional<User> findByTenantIdAndEmail(Long tenantId, String email);
    List<User> findByTenantId(Long tenantId);
    boolean existsByTenantIdAndEmail(Long tenantId, String email);
}
