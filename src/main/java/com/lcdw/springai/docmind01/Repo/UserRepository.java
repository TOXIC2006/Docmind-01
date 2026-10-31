package com.lcdw.springai.docmind01.Repo;

import com.lcdw.springai.docmind01.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByVerificationTokenHash(String verificationTokenHash);
}
