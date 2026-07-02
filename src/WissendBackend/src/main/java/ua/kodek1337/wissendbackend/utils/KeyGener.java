package ua.kodek1337.wissendbackend.utils;

import java.security.SecureRandom;

public class KeyGener{

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom random = new SecureRandom();

    public static String generateRandomString() {
        StringBuilder result = new StringBuilder();
        result.append("WISSEND");
        for (int block = 0; block < 3; block++) {
            result.append("-");
            for (int i = 0; i < 6; i++) {
                int index = random.nextInt(CHARACTERS.length());
                result.append(CHARACTERS.charAt(index));
            }
        }

        return result.toString();
    }
}