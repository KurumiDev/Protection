package ua.kodek1337.wissendbackend.controller.admin;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ua.kodek1337.wissendbackend.repo.UserNewRepo;
import ua.kodek1337.wissendbackend.repo.UserRepo;
import ua.kodek1337.wissendbackend.service.UserService;

import java.util.Map;

@Controller
@RequestMapping("admin/create")
@CrossOrigin(origins = "*")
public class CreateController {
    @Autowired
    private UserService userService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserNewRepo userNewRepo;

    @PostMapping("createKeys")
    private ResponseEntity<?> createKeys(Authentication authentication, @RequestParam int days) {
        if (userRepo.findByUsername(authentication.getName()).get().getBanned() == 1) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username banned"));
        }
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null) {
            return ResponseEntity.status(200).body("Invalid username or password");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.createKeys(days);
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }

    @PostMapping("createPromo")
    private ResponseEntity<?> createPromo(Authentication authentication, @RequestParam String promo, @RequestParam int discount, @RequestParam int days, @RequestParam int activation_out) {
        if (userRepo.findByUsername(authentication.getName()).get().getBanned() == 1) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username banned"));
        }

        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null  || days == 0) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.createPromocode(promo, discount, days, activation_out);
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }

    @PostMapping("createSub")
    private ResponseEntity<?> createSub(Authentication authentication, @RequestParam int id, @RequestParam int days) {
        if (userRepo.findByUsername(authentication.getName()).get().getBanned() == 1) {
            return ResponseEntity.status(403).body(Map.of("Error", "Username banned"));
        }
        if (authentication == null || authentication.getPrincipal() == null || authentication.getPrincipal().equals("anonymousUser") || authentication.getName() == null || id == 0 || days == 0) {
            return ResponseEntity.status(200).body("Invalid response");
        }
        if (userNewRepo.findByUsername(authentication.getName()).getRole().equals("admin")) {
            return userService.createSub(id, days);
        } else {
            return ResponseEntity.status(403).body(Map.of("Error", "Не достаточно прав"));
        }
    }
}
