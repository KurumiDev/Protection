package ua.kodek1337.wissendbackend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ua.kodek1337.wissendbackend.module.UserModule;

import java.util.Optional;

public interface UserRepo extends JpaRepository<UserModule, Long> {
    Optional<UserModule> findByUsername(String username);
    Optional<UserModule> findByEmail(String email);
}
