package ua.kodek1337.wissendbackend.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ua.kodek1337.wissendbackend.module.UserModule;

public interface UserNewRepo extends JpaRepository<UserModule, Integer> {

    UserModule findByUsername(String username);
    UserModule findByEmail(String email);
}
