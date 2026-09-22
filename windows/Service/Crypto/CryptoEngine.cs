using System.Security.Cryptography;
using System.Text;

namespace BiometricUnlock.Service.Crypto;

public class CryptoEngine
{
    private const int NonceByteLength = 32;
    private const int AesGcmTagByteLength = 16;
    private const int AesGcmNonceByteLength = 12;
    private const int ChallengeTtlSeconds = 15;

    // Cache of used nonces to prevent replay attacks
    private readonly HashSet<string> _usedNonces = new();
    private readonly object _nonceLock = new();

    public record ChallengePayload(byte[] Nonce, long TimestampUtc);

    /// <summary>
    /// Generates a cryptographically secure 256-bit challenge nonce and UTC timestamp.
    /// </summary>
    public (byte[] RawPayload, ChallengePayload Challenge) GenerateChallenge()
    {
        byte[] nonce = RandomNumberGenerator.GetBytes(NonceByteLength);
        long timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

        using var ms = new MemoryStream();
        using var writer = new BinaryWriter(ms);
        writer.Write(nonce);
        writer.Write(timestamp);

        byte[] rawPayload = ms.ToArray();
        return (rawPayload, new ChallengePayload(nonce, timestamp));
    }

    /// <summary>
    /// Validates challenge freshness, expiration, and replay prevention.
    /// </summary>
    public bool ValidateChallenge(byte[] rawChallenge)
    {
        if (rawChallenge == null || rawChallenge.Length != (NonceByteLength + sizeof(long)))
        {
            return false;
        }

        using var ms = new MemoryStream(rawChallenge);
        using var reader = new BinaryReader(ms);
        byte[] nonce = reader.ReadBytes(NonceByteLength);
        long timestamp = reader.ReadInt64();

        long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        if (Math.Abs(now - timestamp) > ChallengeTtlSeconds)
        {
            return false; // Expired or future timestamp
        }

        string nonceHex = Convert.ToHexString(nonce);
        lock (_nonceLock)
        {
            if (_usedNonces.Contains(nonceHex))
            {
                return false; // Replay detected!
            }
            _usedNonces.Add(nonceHex);
            
            // Clean up old nonces when cache grows
            if (_usedNonces.Count > 1000)
            {
                _usedNonces.Clear();
            }
        }

        return true;
    }

    /// <summary>
    /// Verifies the ECDSA P-256 signature produced by the phone's hardware keystore.
    /// </summary>
    public bool VerifySignature(byte[] dataToVerify, byte[] signature, byte[] phonePublicKeyDer)
    {
        try
        {
            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(phonePublicKeyDer, out _);
            return ecdsa.VerifyData(dataToVerify, signature, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation)
                || ecdsa.VerifyData(dataToVerify, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// Derives an ephemeral AES-256 session key using ECDH P-256 and HKDF-SHA256.
    /// </summary>
    public (byte[] EphemeralPublicKeyDer, byte[] AesKey) GenerateServerEcdhSession(byte[] clientPublicKeyDer)
    {
        using var serverEcdh = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        using var clientEcdh = ECDiffieHellman.Create();
        clientEcdh.ImportSubjectPublicKeyInfo(clientPublicKeyDer, out _);

        byte[] sharedSecret = serverEcdh.DeriveKeyMaterial(clientEcdh.PublicKey);
        byte[] serverPublicKeyDer = serverEcdh.ExportSubjectPublicKeyInfo();

        // Derive 256-bit AES key using HKDF
        byte[] salt = Encoding.UTF8.GetBytes("BiometricUnlockSaltV1");
        byte[] info = Encoding.UTF8.GetBytes("BiometricUnlockAesSessionKey");
        byte[] aesKey = HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, 32, salt, info);

        return (serverPublicKeyDer, aesKey);
    }

    /// <summary>
    /// Decrypts an authenticated AES-256-GCM payload.
    /// </summary>
    public byte[] DecryptAesGcm(byte[] ciphertextWithNonceAndTag, byte[] key)
    {
        if (ciphertextWithNonceAndTag.Length < (AesGcmNonceByteLength + AesGcmTagByteLength))
        {
            throw new CryptographicException("Ciphertext payload too short.");
        }

        byte[] nonce = ciphertextWithNonceAndTag[..AesGcmNonceByteLength];
        int tagOffset = ciphertextWithNonceAndTag.Length - AesGcmTagByteLength;
        byte[] tag = ciphertextWithNonceAndTag[tagOffset..];
        byte[] ciphertext = ciphertextWithNonceAndTag[AesGcmNonceByteLength..tagOffset];

        byte[] plaintext = new byte[ciphertext.Length];
        using var aesGcm = new AesGcm(key, AesGcmTagByteLength);
        aesGcm.Decrypt(nonce, ciphertext, tag, plaintext);

        return plaintext;
    }

    /// <summary>
    /// Encrypts data using AES-256-GCM.
    /// </summary>
    public byte[] EncryptAesGcm(byte[] plaintext, byte[] key)
    {
        byte[] nonce = RandomNumberGenerator.GetBytes(AesGcmNonceByteLength);
        byte[] ciphertext = new byte[plaintext.Length];
        byte[] tag = new byte[AesGcmTagByteLength];

        using var aesGcm = new AesGcm(key, AesGcmTagByteLength);
        aesGcm.Encrypt(nonce, plaintext, ciphertext, tag);

        byte[] result = new byte[nonce.Length + ciphertext.Length + tag.Length];
        Buffer.BlockCopy(nonce, 0, result, 0, nonce.Length);
        Buffer.BlockCopy(ciphertext, 0, result, nonce.Length, ciphertext.Length);
        Buffer.BlockCopy(tag, 0, result, nonce.Length + ciphertext.Length, tag.Length);

        return result;
    }
}
