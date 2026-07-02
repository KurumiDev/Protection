package ua.kodek1337.wissendbackend.controller.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import ua.kodek1337.wissendbackend.service.UserService;

import javax.swing.*;
import java.util.Map;

@Controller
@RequestMapping("auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("register")
    public ResponseEntity<?> registerUser(@RequestParam String username, @RequestParam String password, @RequestParam String email) {
        if (username.isEmpty() || password.isEmpty() || email.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("Error", "Server not exits"));
        } else {
            return userService.register(username, password, email);
        }
    }

    @PostMapping("login")
    public ResponseEntity<?> login(@RequestParam String username, @RequestParam String password) {
        if (username.isEmpty() || password.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("Error", "Server not exits"));
        } else {
            return userService.login(username, password);
        }
    }
}
