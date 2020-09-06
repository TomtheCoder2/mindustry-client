package mindustry.client;

import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.client.utils.*;
import sun.security.rsa.*;

import java.math.*;
import java.security.*;
import java.security.interfaces.*;

public class RSAChat{
//    static {
//        if(!(Core.settings.has("crypto-P") && settings.has("crypto-Q"))){
//            RSA rsa = new RSA(256);
//            settings.put("crypto-P", rsa.p);
//            settings.put("crypto-Q", rsa.q);
//        }
//    }
//    public static final RSA rsa = new RSA(256, (BigInteger)settings.get("crypto-P", new BigInteger("1")), (BigInteger)settings.get("crypto-Q", new BigInteger("1")));
    private static final ObjectMap<Integer, byte[]> keyDatabase = new ObjectMap<>();
    private static int id = 1;
    private static PrivateKey privateKey;
    private static PublicKey publicKey;
    {
        try{
            KeyPair pair = RSA.generateKeyPair();
            privateKey = pair.getPrivate();
            publicKey = pair.getPublic();
        }catch(Exception e){
            e.printStackTrace();
        }
        keyDatabase.put(id, publicKey.getEncoded());
    }

    public static void main(String[] args) throws Exception{
        System.out.println(encryptChat("Hello!", 1));
    }

    public static String encryptChat(String message, int recipientId){
        try{
            RSAPublicKey senderKey = RSAPublicKeyImpl.newKey(keyDatabase.get(recipientId));
            String ciphertext = RSA.encrypt(message, senderKey);
            return id + "%" + ciphertext + "%" + RSA.sign(ciphertext, privateKey);
        }catch(Exception error){ //reeeee "throws Exception" why would you do this to me
            error.printStackTrace();
            return "";
        }
    }

    public static String decryptChat(String message){
        try{
            int senderId = Strings.parseInt(message.split("%")[0]);
            RSAPublicKey senderKey = RSAPublicKeyImpl.newKey(keyDatabase.get(senderId));
            String plaintext = RSA.decrypt(message.split("%")[1], privateKey);
            boolean valid = RSA.verify(message.split("%")[1], message.split("%")[2], senderKey);
            return plaintext + String.format(" | %s signature from %d", valid? "good" : "[red]BAD[]", senderId);
        }catch(Exception error){
            error.printStackTrace();
            return "";
        }
    }
}
