package cl.duoc.barriodigital.notify.consumer.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Comprueba si un texto es una sola dirección de correo.
 * No acepta listas, nombres visibles ni saltos de línea, y no consulta DNS para ver si el dominio existe.
 */
public final class EmailAddress {

    /** Nadie instancia esta clase: solo se usa isValid. */
    private EmailAddress() {
    }

    /**
     * Dice si el texto es una sola dirección de correo.
     * Rechaza listas, grupos, nombres visibles y saltos de línea. No pregunta al DNS.
     */
    public static boolean isValid(String value) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return false;
        }
        try {
            InternetAddress[] addresses = InternetAddress.parse(value.trim(), true);
            if (addresses.length != 1 || addresses[0].isGroup() || addresses[0].getPersonal() != null
                    || !value.trim().equals(addresses[0].getAddress())) {
                return false;
            }
            addresses[0].validate();
            return true;
        } catch (AddressException e) {
            return false;
        }
    }
}
