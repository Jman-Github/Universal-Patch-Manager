import java.net.*;import java.io.*;import java.util.*;import java.security.*;
public class ShrunkHostCheck {
 public static void main(String[] args) throws Exception {
  try(URLClassLoader engine=new URLClassLoader(new URL[]{new File(args[0]).toURI().toURL()},ClassLoader.getPlatformClassLoader())) {
   Thread.currentThread().setContextClassLoader(engine);
   String entry=args[1].equals("morphe")?"app.universal.patch.manager.morphe.runtime.MorpheRuntimeEntry":"app.universal.patch.manager.revanced.runtime.RevancedRuntimeEntry";
   if(args[1].equals("morphe")) {
    var match=engine.loadClass("app.morphe.patcher.Match$InstructionMatch");
    for(String getter:List.of("getFieldReference","getMethodReference")) {
     var method=match.getMethod(getter);
     String expected=getter.equals("getFieldReference")?"FieldReference":"MethodReference";
     if(!method.getReturnType().getName().equals("com.android.tools.smali.dexlib2.iface.reference."+expected))
      throw new AssertionError("Incorrect SDK method descriptor: "+method);
    }
    System.out.println("PASS Morphe instruction reference APIs");
   }
   if(args.length>2) {
    List<?> result=(List<?>)engine.loadClass(entry).getMethod("loadMetadataForBundle",String.class).invoke(null,args[2]);
    if(result.isEmpty())throw new AssertionError("Empty metadata");
    System.out.println("PASS shrunk metadata "+args[1]+": "+result.size()+" patches");
   }
   var factory=engine.loadClass("org.xmlpull.v1.XmlPullParserFactory");
   Object xml=factory.getMethod("newInstance").invoke(null);
   Object parser=factory.getMethod("newPullParser").invoke(xml);
   var parserType=engine.loadClass("org.xmlpull.v1.XmlPullParser");
   parserType.getMethod("setInput",Reader.class).invoke(parser,new StringReader("<resources><string name='test'>value</string></resources>"));
   while(((Integer)parserType.getMethod("next").invoke(parser))!=1) {}
   System.out.println("PASS shrunk XML parser");
   try {
    Provider bc=(Provider)engine.loadClass("org.bouncycastle.jce.provider.BouncyCastleProvider").getConstructor().newInstance();
    for(String algorithm:List.of("SHA256withRSA","SHA256withECDSA","SHA256withDSA")) {
     String key=algorithm.endsWith("RSA")?"RSA":algorithm.endsWith("ECDSA")?"EC":"DSA";
     var generator=KeyPairGenerator.getInstance(key);generator.initialize(key.equals("EC")?256:2048);
     var pair=generator.generateKeyPair();
     var signature=Signature.getInstance(algorithm,bc);signature.initSign(pair.getPrivate());signature.update(new byte[]{1,2,3});
     var signed=signature.sign();signature.initVerify(pair.getPublic());signature.update(new byte[]{1,2,3});
     if(!signature.verify(signed))throw new AssertionError(algorithm);
    }
    System.out.println("PASS shrunk signing providers");
   } catch(ClassNotFoundException missing) {if(!args[1].equals("revanced-legacy"))throw missing;}
  }
 }
}