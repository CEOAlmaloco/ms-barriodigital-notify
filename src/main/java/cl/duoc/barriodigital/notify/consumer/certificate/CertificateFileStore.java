package cl.duoc.barriodigital.notify.consumer.certificate;

import cl.duoc.barriodigital.notify.config.CertificateProperties;
import cl.duoc.barriodigital.notify.messaging.RecoverableException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Guarda el PDF en una carpeta.
 * Primero lo escribe en un archivo temporal y, cuando está listo, lo mueve al nombre final. Si el disco falla, borra el temporal y avisa que se puede reintentar.
 */
@Component
public class CertificateFileStore {
    private final Path directory;
    private final CertificatePdfGenerator generator;

    /** Fija la carpeta absoluta y quién dibuja el PDF. */
    public CertificateFileStore(CertificateProperties properties, CertificatePdfGenerator generator) {
        this.directory = Path.of(properties.outputDir()).toAbsolutePath().normalize();
        this.generator = generator;
    }

    /**
     * Escribe primero un archivo temporal y después lo mueve al nombre final.
     * Si el disco falla, borra el temporal y avisa que se puede reintentar.
     */
    public Path save(CertificateDocument document) {
        Path temporary = null;
        try {
            ensureDirectory();
            Path target = directory.resolve(fileName(document));
            checkTarget(target);
            temporary = Files.createTempFile(directory, ".certificate-", ".part");
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                generator.write(document, output);
            }
            // El directorio es administrado por el servicio. Se vuelve a comprobar antes de publicar.
            ensureDirectory();
            checkTarget(target);
            publish(temporary, target);
            return target;
        } catch (IOException | RuntimeException failure) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof IOException || failure instanceof SecurityException) {
                throw new RecoverableException("no se pudo guardar el certificado PDF", failure);
            }
            throw (RuntimeException) failure;
        }
    }

    /**
     * Arma el nombre certificate-receipt- o certificate-resolved- más el SHA-256 del eventId.
     * Así el eventId no puede salirse de la carpeta.
     */
    static String fileName(CertificateDocument document) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(document.eventId().getBytes(StandardCharsets.UTF_8));
            return "certificate-" + document.kind().filePart() + "-" + HexFormat.of().formatHex(digest) + ".pdf";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible", e);
        }
    }

    /** Crea la carpeta parte por parte y rechaza enlaces simbólicos o archivos en el camino. */
    private void ensureDirectory() throws IOException {
        Path current = directory.getRoot();
        for (Path part : directory) {
            current = current.resolve(part);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                    throw new IOException("el directorio de certificados contiene un enlace o un archivo", e);
                }
            }
            if (!current.toRealPath().equals(current)) {
                throw new IOException("el directorio de certificados redirige a otro destino");
            }
        }
    }

    /** Comprueba que el destino esté dentro de la carpeta y no sea un enlace. */
    private void checkTarget(Path target) throws IOException {
        if (!target.normalize().getParent().equals(directory)
                || Files.isSymbolicLink(target)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("destino de certificado no permitido");
        }
    }

    /** Mueve el temporal al nombre final de una sola vez. Si falla, no hace una copia de reemplazo. */
    void publish(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
