package ua.kodek1337.wissendbackend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import ua.kodek1337.wissendbackend.module.UserModule;
import ua.kodek1337.wissendbackend.repo.SubRepo;
import ua.kodek1337.wissendbackend.repo.UserRepo;
import ua.kodek1337.wissendbackend.repo.VersionRepo;
import ua.kodek1337.wissendbackend.service.UserService;

import java.time.LocalDate;
import java.util.Map;

@Controller
@RequestMapping("main")
@CrossOrigin(origins = "*")
public class MainController {

    @Autowired
    private VersionRepo versionRepo;

    @Autowired
    private SubRepo subRepo;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepo userRepo;

    @PostMapping("clientVersion")
    private ResponseEntity<?> giveVersionClient() {

            return ResponseEntity.status(200).body(Map.of("Version", versionRepo.findById(1).get()));

    }
}
