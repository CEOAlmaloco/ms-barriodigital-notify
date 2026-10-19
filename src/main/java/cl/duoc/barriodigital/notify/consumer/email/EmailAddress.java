package cl.duoc.barriodigital.notify.consumer.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** Dirección única, sin listas, grupos, nombres visibles ni saltos de línea. No consulta DNS. */
public final class EmailAddress {

    private EmailAddress() {
    }

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
