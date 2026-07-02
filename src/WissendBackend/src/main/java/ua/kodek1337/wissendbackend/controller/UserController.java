package ua.kodek1337.wissendbackend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ua.kodek1337.wissendbackend.repo.UserRepo;
import ua.kodek1337.wissendbackend.service.UserService;

import java.util.Map;

@Controller
@RequestMapping("user")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepo userRepo;

    @PostMapping("getMyProfile")
    private ResponseEntity<?> getMyProfile(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.badRequest().build();
        } else {
            return userService.getMyProfyle(authentication.getName());
        }
    }


    @PostMapping("getAdmin")
    private ResponseEntity<?> getAdmin(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.badRequest().build();
        }

        if (userRepo.findByUsername(authentication.getName()).get().getRole().equals("admin")) {
            return ResponseEntity.status(200).body(Map.of("Admin", "is active"));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("activeKey")
    private ResponseEntity<?> activeKey(Authentication authentication, @RequestParam String value) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null || value == null) {
            return ResponseEntity.badRequest().build();
        } else {
            return userService.activeKey(authentication.getName(), value);
        }
    }

    @PostMapping("repass")
    private ResponseEntity<?> repass(Authentication authentication,  @RequestParam String oldPass, @RequestParam String newPass) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.badRequest().build();
        }
        return userService.repass(authentication.getName(), oldPass, newPass);
    }

    @PostMapping("getSub")
    private ResponseEntity<?> getSub(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.badRequest().build();
        }
        return userService.getSub(authentication.getName());
    }
}
