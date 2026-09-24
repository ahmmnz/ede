package com.googletv.kumanda;

import android.Manifest;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import android.content.SharedPreferences;
import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class MainActivity extends AppCompatActivity {

    enum Mode { WIFI_ADB, BT, PIN }
    Mode currentMode = Mode.PIN;

    int ses=24, kanal=7;
    TextView volText,chText,statusText,logText,testResult;
    EditText ipInput, pinCodeInput;
    LinearLayout wifiLayout, btLayout, pinLayout;
    Spinner btSpinner;
    ArrayAdapter<String> btAdapter;
    List<BluetoothDevice> btDevices=new ArrayList<>();
    BluetoothAdapter btAdapterHW;
    BluetoothSocket btSocket;
    OutputStream btOut;

    Socket adbSocket; DataInputStream adbIn; DataOutputStream adbOut; boolean adbConnected=false; int localId=1;
    static final int A_CNXN=0x4e584e43, A_OPEN=0x4e45504f;

    // Professional remote
    ProfessionalRemote remote;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        volText=findViewById(R.id.volText); chText=findViewById(R.id.chText);
        statusText=findViewById(R.id.statusText); logText=findViewById(R.id.logText); testResult=findViewById(R.id.testResult);
        ipInput=findViewById(R.id.ipInput); pinCodeInput=findViewById(R.id.pinCodeInput);
        wifiLayout=findViewById(R.id.wifiLayout); btLayout=findViewById(R.id.btLayout); pinLayout=findViewById(R.id.pinLayout);
        btSpinner=findViewById(R.id.btSpinner);
        btAdapterHW=BluetoothAdapter.getDefaultAdapter();
        btAdapter=new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        btAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        btSpinner.setAdapter(btAdapter);
        SharedPreferences prefs=getSharedPreferences("gtv",MODE_PRIVATE);
        ipInput.setText(prefs.getString("tv_ip",""));
        remote = new ProfessionalRemote(this);

        RadioGroup modeGroup=findViewById(R.id.modeGroup);
        modeGroup.setOnCheckedChangeListener((g,id)->{
            if(id==R.id.radioWifiAdb){ currentMode=Mode.WIFI_ADB; wifiLayout.setVisibility(LinearLayout.VISIBLE); btLayout.setVisibility(LinearLayout.GONE); pinLayout.setVisibility(LinearLayout.GONE); }
            else if(id==R.id.radioBt){ currentMode=Mode.BT; wifiLayout.setVisibility(LinearLayout.GONE); btLayout.setVisibility(LinearLayout.VISIBLE); pinLayout.setVisibility(LinearLayout.GONE); checkPerm(); }
            else { currentMode=Mode.PIN; wifiLayout.setVisibility(LinearLayout.GONE); btLayout.setVisibility(LinearLayout.GONE); pinLayout.setVisibility(LinearLayout.VISIBLE); }
        });

        findViewById(R.id.btnTestPorts).setOnClickListener(v->testPorts());
        findViewById(R.id.btnWifiConnect).setOnClickListener(v->{
            String ip=ipInput.getText().toString().trim();
            if(ip.isEmpty()){ toast("IP gir"); return; }
            prefs.edit().putString("tv_ip",ip).apply();
            connectAdb(ip);
        });
        findViewById(R.id.btnBtEnable).setOnClickListener(v->{
            if(btAdapterHW==null){ toast("BT yok"); return; }
            if(!btAdapterHW.isEnabled()){
                if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){ ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.BLUETOOTH_CONNECT},1); return; }
                btAdapterHW.enable();
            }
        });
        findViewById(R.id.btnBtScan).setOnClickListener(v->scanBt());
        findViewById(R.id.btnBtConnect).setOnClickListener(v->{
            if(btDevices.isEmpty()){ toast("Once Tara"); return; }
            int pos=btSpinner.getSelectedItemPosition();
            if(pos<0||pos>=btDevices.size()){ toast("Cihaz sec"); return; }
            connectBt(btDevices.get(pos));
        });
        findViewById(R.id.btnPinStart).setOnClickListener(v->{
            String ip=ipInput.getText().toString().trim();
            if(ip.isEmpty()){ toast("IP gir"); return; }
            prefs.edit().putString("tv_ip",ip).apply();
            remote.startPairing(ip);
        });
        findViewById(R.id.btnPinSend).setOnClickListener(v->{
            String code=pinCodeInput.getText().toString().trim().toUpperCase();
            if(code.isEmpty()){ toast("6 haneli kod gir"); return; }
            remote.sendSecret(code);
        });
        findViewById(R.id.btnPinRemote).setOnClickListener(v->{
            String ip=ipInput.getText().toString().trim();
            if(ip.isEmpty()){ toast("IP gir"); return; }
            remote.connectRemote(ip);
        });
        findViewById(R.id.btnTestKey).setOnClickListener(v->{
            remote.sendKey(3, "HOME TEST");
        });

        findViewById(R.id.btnPower).setOnClickListener(v->sendCommand(26,"POWER"));
        findViewById(R.id.btnMute).setOnClickListener(v->sendCommand(164,"MUTE"));
        findViewById(R.id.btnUp).setOnClickListener(v->sendCommand(19,"UP"));
        findViewById(R.id.btnDown).setOnClickListener(v->sendCommand(20,"DOWN"));
        findViewById(R.id.btnLeft).setOnClickListener(v->sendCommand(21,"LEFT"));
        findViewById(R.id.btnRight).setOnClickListener(v->sendCommand(22,"RIGHT"));
        findViewById(R.id.btnOk).setOnClickListener(v->sendCommand(23,"OK"));
        findViewById(R.id.btnBack).setOnClickListener(v->sendCommand(4,"BACK"));
        findViewById(R.id.btnHome).setOnClickListener(v->sendCommand(3,"HOME"));
        findViewById(R.id.btnMenu).setOnClickListener(v->sendCommand(82,"MENU"));
        findViewById(R.id.btnVolUp).setOnClickListener(v->sendCommand(24,"VOL_UP",()->{ if(ses<100)ses++; updateUI(); }));
        findViewById(R.id.btnVolDown).setOnClickListener(v->sendCommand(25,"VOL_DOWN",()->{ if(ses>0)ses--; updateUI(); }));
        findViewById(R.id.btnChUp).setOnClickListener(v->sendCommand(166,"CH_UP",()->{ kanal++; if(kanal>999)kanal=1; updateUI(); }));
        findViewById(R.id.btnChDown).setOnClickListener(v->sendCommand(167,"CH_DOWN",()->{ kanal--; if(kanal<1)kanal=999; updateUI(); }));
        updateUI();
    }

    void log(String s){ runOnUiThread(()->{ logText.append("\n"+s); }); }
    void status(String s){ runOnUiThread(()->statusText.setText(s)); }

    void testPorts(){
        String ip=ipInput.getText().toString().trim();
        if(ip.isEmpty()){ toast("IP gir"); return; }
        testResult.setText("Test ediliyor "+ip+" ...");
        new Thread(()->{
            StringBuilder sb=new StringBuilder();
            int[] ports={5555,6466,6467};
            for(int port: ports){
                try{
                    Socket s=new Socket(); s.connect(new InetSocketAddress(ip,port),2000); s.close();
                    sb.append("Port "+port+" ACIK ✔\n");
                }catch(Exception e){ sb.append("Port "+port+" KAPALI ✘\n"); }
            }
            runOnUiThread(()->testResult.setText(sb.toString()));
        }).start();
    }

    void connectAdb(String ip){
        status("ADB baglaniyor "+ip+":5555");
        new Thread(()->{
            try{
                if(adbSocket!=null) try{adbSocket.close();}catch(Exception e){}
                adbSocket=new Socket(); adbSocket.connect(new InetSocketAddress(ip,5555),5000);
                adbIn=new DataInputStream(adbSocket.getInputStream()); adbOut=new DataOutputStream(adbSocket.getOutputStream());
                sendAdb(A_CNXN,0x01000000,256*1024,"host::\0".getBytes());
                AdbMsg r=readAdb();
                adbConnected=true;
                status("ADB Bagli: "+ip); toast("ADB Baglandi");
            }catch(Exception e){ adbConnected=false; status("ADB hata: "+e.getMessage()); }
        }).start();
    }
    void sendAdb(int cmd,int a0,int a1,byte[] data) throws IOException{
        if(data==null) data=new byte[0];
        ByteBuffer b=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd); b.putInt(a0); b.putInt(a1); b.putInt(data.length); b.putInt(checksum(data)); b.putInt(cmd ^ 0xFFFFFFFF);
        adbOut.write(b.array()); if(data.length>0) adbOut.write(data); adbOut.flush();
    }
    AdbMsg readAdb() throws IOException{
        byte[] h=new byte[24]; adbIn.readFully(h);
        ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd=b.getInt(),a0=b.getInt(),a1=b.getInt(),len=b.getInt(),crc=b.getInt(),mag=b.getInt();
        byte[] d=new byte[len]; if(len>0) adbIn.readFully(d);
        return new AdbMsg(cmd,a0,a1,d);
    }
    int checksum(byte[] d){ int s=0; for(byte x:d) s+=x&0xFF; return s; }
    static class AdbMsg{ int c,a0,a1; byte[] d; AdbMsg(int c,int a0,int a1,byte[] d){this.c=c;this.a0=a0;this.a1=a1;this.d=d;} }
    void execAdb(int keyCode){
        if(!adbConnected) return;
        new Thread(()->{
            try{
                String cmd="shell:input keyevent "+keyCode+"\0";
                sendAdb(A_OPEN,localId++,0,cmd.getBytes());
                for(int i=0;i<2;i++) try{readAdb();}catch(Exception e){}
            }catch(Exception e){}
        }).start();
    }

    void scanBt(){
        checkPerm();
        if(btAdapterHW==null||!btAdapterHW.isEnabled()){ toast("BT acik degil"); return; }
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){ ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.BLUETOOTH_CONNECT},1); return; }
        Set<BluetoothDevice> paired=btAdapterHW.getBondedDevices();
        btDevices.clear(); List<String> names=new ArrayList<>();
        for(BluetoothDevice d:paired){ btDevices.add(d); names.add(d.getName()+" ("+d.getAddress()+")"); }
        if(names.isEmpty()) names.add("Eslesmis cihaz yok");
        btAdapter.clear(); btAdapter.addAll(names); btAdapter.notifyDataSetChanged();
    }
    void connectBt(BluetoothDevice dev){
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){ ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.BLUETOOTH_CONNECT},1); return; }
        status("BT baglaniyor: "+dev.getName());
        new Thread(()->{
            try{
                java.util.UUID uuid=java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
                btSocket=dev.createRfcommSocketToServiceRecord(uuid);
                btSocket.connect(); btOut=btSocket.getOutputStream();
                status("BT Bagli: "+dev.getName());
            }catch(Exception e){ status("BT hata: "+e.getMessage()); }
        }).start();
    }
    void checkPerm(){
        List<String> p=new ArrayList<>();
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN);
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!p.isEmpty()) ActivityCompat.requestPermissions(this,p.toArray(new String[0]),1);
    }

    void sendCommand(int keyCode,String name){ sendCommand(keyCode,name,null); }
    void sendCommand(int keyCode,String name,Runnable ok){
        if(currentMode==Mode.WIFI_ADB){
            if(adbConnected){ execAdb(keyCode); toast(name+" -> ADB"); } else toast(name+" [ADB bagli degil]");
        } else if(currentMode==Mode.BT){
            if(btSocket!=null && btSocket.isConnected() && btOut!=null){
                try{ btOut.write((name+"\n").getBytes()); toast(name+" -> BT"); }catch(Exception e){ toast("BT hata"); }
            } else toast(name+" [BT bagli degil]");
        } else {
            remote.sendKey(keyCode, name);
        }
        if(ok!=null) ok.run();
    }

    void updateUI(){ volText.setText(String.valueOf(ses)); chText.setText(String.valueOf(kanal)); }
    void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }

    // ================= PROFESYONEL REMOTE SINIFI =================
    static class ProfessionalRemote {
        MainActivity act;
        Socket pinSocket, remoteSocket;
        DataInputStream pinIn, remoteIn;
        DataOutputStream pinOut, remoteOut;
        KeyPair keyPair; X509Certificate cert;
        byte[] serverMod, serverExp, clientMod, clientExp;
        volatile boolean remoteRunning=false;
        Thread readerThread;
        LinkedBlockingQueue<byte[]> cmdQueue = new LinkedBlockingQueue<>();
        Thread writerThread;

        ProfessionalRemote(MainActivity a){ act=a; }

        void log(String s){ act.log(s); }
        void status(String s){ act.status(s); }

        void startPairing(String ip){
            status("V2 Pairing basliyor "+ip+":6467");
            log("Pairing start "+ip);
            new Thread(()->{
                try{
                    genCert();
                    SSLSocketFactory f = createFactory();
                    SSLSocket s = (SSLSocket) f.createSocket();
                    s.connect(new InetSocketAddress(ip,6467),5000);
                    s.setSoTimeout(15000);
                    s.startHandshake();
                    log("TLS 6467 handshake OK");

                    X509Certificate sc = (X509Certificate) s.getSession().getPeerCertificates()[0];
                    RSAPublicKey sp = (RSAPublicKey) sc.getPublicKey();
                    serverMod = sp.getModulus().toByteArray();
                    serverExp = sp.getPublicExponent().toByteArray();
                    RSAPublicKey cp = (RSAPublicKey) keyPair.getPublic();
                    clientMod = cp.getModulus().toByteArray();
                    clientExp = cp.getPublicExponent().toByteArray();

                    pinSocket=s; pinIn=new DataInputStream(s.getInputStream()); pinOut=new DataOutputStream(s.getOutputStream());

                    // Pairing message
                    byte[] service = "info.kodono.assistant".getBytes();
                    byte[] cname = "GTV Kumanda".getBytes();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    out.write(new byte[]{8,2,16,(byte)200,1,82,43});
                    out.write(10); out.write(service.length); out.write(service);
                    out.write(18); out.write(cname.length); out.write(cname);
                    writeVar(out.toByteArray());
                    log("Pairing sent "+out.size());

                    byte[] r = readVar(); log("Pair ack "+(r!=null?r.length:-1));

                    byte[] opt = new byte[]{8,2,16,(byte)200,1,(byte)162,1,8,10,4,8,3,16,6,24,1};
                    writeVar(opt); log("Option sent");
                    byte[] r2 = readVar(); log("Option ack "+(r2!=null?r2.length:-1));

                    byte[] cfg = new byte[]{8,2,16,(byte)200,1,(byte)242,1,8,10,4,8,3,16,6,16,1};
                    writeVar(cfg); log("Config sent - TV'de 6 haneli kod cikmali!");
                    byte[] r3 = readVar(); log("Config ack "+(r3!=null?r3.length:-1));

                    status("TV'de 6 HANELI KOD cikti! Gir ve Dogrula");
                }catch(Exception e){ log("Pair hata: "+e); status("Pair hata: "+e.getMessage()); e.printStackTrace(); }
            }).start();
        }

        void sendSecret(String code){
            new Thread(()->{
                try{
                    if(pinOut==null){ status("Once Kodu Goster"); return; }
                    log("Secret kod="+code);
                    String last4 = code.length()>=4 ? code.substring(code.length()-4) : code;
                    byte[] codeBin;
                    try{ codeBin = hexToBytes(last4); }catch(Exception ex){ codeBin = last4.getBytes(); }

                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update(trim(clientMod)); md.update(trim(clientExp));
                    md.update(trim(serverMod)); md.update(trim(serverExp));
                    md.update(codeBin);
                    byte[] hash = md.digest();

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    out.write(new byte[]{8,2,16,(byte)200,1,(byte)194,2,34,10,32});
                    out.write(hash);
                    writeVar(out.toByteArray());
                    log("Secret sent");

                    byte[] r = readVar();
                    log("Secret ack "+(r!=null?r.length:-1));
                    if(r!=null){ status("PIN OK! Simdi 6466 Bagla"); }
                    else status("Secret ack yok - kod yanlis olabilir");
                }catch(Exception e){ log("Secret hata: "+e); status("Secret hata"); }
            }).start();
        }

        void connectRemote(String ip){
            if(remoteRunning){ try{remoteSocket.close();}catch(Exception e){} remoteRunning=false; }
            status("6466 baglaniyor... "+ip);
            log("6466 connect "+ip);
            new Thread(()->{
                try{
                    SSLSocketFactory f = createFactory();
                    SSLSocket s = (SSLSocket) f.createSocket();
                    s.connect(new InetSocketAddress(ip,6466),5000);
                    s.setSoTimeout(0);
                    s.startHandshake();
                    remoteSocket=s; remoteIn=new DataInputStream(s.getInputStream()); remoteOut=new DataOutputStream(s.getOutputStream());
                    log("6466 TLS OK");

                    // Server info
                    try{ byte[] info = readVarRemote(); log("Server info len="+(info!=null?info.length:-1)); }catch(Exception e){ log("Server info read fail "+e); }

                    // cfg1
                    byte[] cfg1 = new byte[]{10,34,8,(byte)238,4,18,29,24,1,34,1,49,42,15,97,110,100,114,111,105,116,118,45,114,101,109,111,116,101,50,5,49,46,48,46,48};
                    writeVarRemote(cfg1); log("cfg1 sent");
                    try{ byte[] a1 = readVarRemote(); log("cfg1 ack1 "+(a1!=null?a1.length:-1)); }catch(Exception e){ log("cfg1 ack1 timeout"); }
                    try{ byte[] a2 = readVarRemote(); log("cfg1 ack2 "+(a2!=null?a2.length:-1)); }catch(Exception e){ log("cfg1 ack2 timeout"); }

                    byte[] cfg2 = new byte[]{18,3,8,(byte)238,4};
                    writeVarRemote(cfg2); log("cfg2 sent");

                    // Start reader thread with ping/pong
                    remoteRunning=true;
                    readerThread = new Thread(()->{
                        while(remoteRunning){
                            try{
                                byte[] data = readVarRemote();
                                if(data==null) break;
                                // Ping detection: data starts with 66,6 or contains ping
                                if(data.length>=2 && (data[0]&0xFF)==66){
                                    log("Ping geldi -> Pong gonderiliyor");
                                    byte[] pong = new byte[]{74,2,8,25};
                                    writeVarRemote(pong);
                                    log("Pong gonderildi");
                                } else {
                                    log("Remote msg len="+data.length+" b0="+(data.length>0?data[0]:-1));
                                }
                            }catch(Exception e){ log("Reader hata: "+e); break; }
                        }
                    });
                    readerThread.start();

                    // Writer thread for queued commands
                    writerThread = new Thread(()->{
                        while(remoteRunning){
                            try{
                                byte[] cmd = cmdQueue.take();
                                writeVarRemote(cmd);
                            }catch(Exception e){ break; }
                        }
                    });
                    writerThread.start();

                    status("6466 BAGLI - Tuslar aktif! TEST'e bas");
                    log("6466 BAGLI - hazir");

                }catch(Exception e){ log("6466 hata: "+e); status("6466 hata: "+e.getMessage()); e.printStackTrace(); }
            }).start();
        }

        void sendKey(int keyCode, String name){
            if(remoteOut==null){ act.toast(name+" [6466 bagli degil]"); return; }
            try{
                byte[] press = new byte[]{82,4,8,(byte)keyCode,16,1};
                byte[] release = new byte[]{82,4,8,(byte)keyCode,16,2};
                // For channel up/down use single message with 16,3
                if(keyCode==166 || keyCode==167){
                    byte[] single = new byte[]{82,5,8,(byte)keyCode,1,16,3};
                    cmdQueue.offer(single);
                    log("Key "+name+" ("+keyCode+") single sent");
                } else {
                    cmdQueue.offer(press);
                    new Thread(()->{
                        try{ Thread.sleep(60); cmdQueue.offer(release); }catch(Exception e){}
                    }).start();
                    log("Key "+name+" ("+keyCode+") press/release queued");
                }
                act.toast(name+" -> 6466");
            }catch(Exception e){ log("Send key hata "+e); }
        }

        void writeVar(byte[] payload) throws IOException{
            writeVarInt(pinOut, payload.length);
            pinOut.write(payload); pinOut.flush();
        }
        byte[] readVar() throws IOException{
            int len = readVarInt(pinIn);
            if(len<=0||len>10000) return null;
            byte[] data=new byte[len]; pinIn.readFully(data); return data;
        }
        void writeVarRemote(byte[] payload) throws IOException{
            writeVarInt(remoteOut, payload.length);
            remoteOut.write(payload); remoteOut.flush();
        }
        byte[] readVarRemote() throws IOException{
            int len = readVarInt(remoteIn);
            if(len<=0||len>10000) return null;
            byte[] data=new byte[len]; remoteIn.readFully(data); return data;
        }
        void writeVarInt(DataOutputStream out, int value) throws IOException{
            while((value & ~0x7F)!=0){
                out.write((value & 0x7F) | 0x80);
                value >>>=7;
            }
            out.write(value);
        }
        int readVarInt(DataInputStream in) throws IOException{
            int result=0, shift=0;
            while(true){
                int b=in.read(); if(b==-1) throw new EOFException();
                result |= (b & 0x7F) << shift;
                if((b & 0x80)==0) break;
                shift+=7;
                if(shift>28) throw new IOException("Varint too long");
            }
            return result;
        }

        SSLSocketFactory createFactory() throws Exception{
            if(keyPair==null) genCert();
            KeyStore ks=KeyStore.getInstance("PKCS12");
            ks.load(null,null);
            ks.setKeyEntry("client", keyPair.getPrivate(), "".toCharArray(), new java.security.cert.Certificate[]{cert});
            KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "".toCharArray());
            TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){ return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c,String a){}
                public void checkServerTrusted(X509Certificate[] c,String a){}
            }};
            SSLContext ctx=SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), trustAll, new SecureRandom());
            return ctx.getSocketFactory();
        }

        void genCert() throws Exception{
            KeyPairGenerator kpg=KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            keyPair=kpg.generateKeyPair();
            X500Name dn=new X500Name("CN=atvremote");
            long now=System.currentTimeMillis();
            Date nb=new Date(now-1000L*60*60);
            Date na=new Date(now+1000L*60*60*24*365*10);
            java.math.BigInteger serial=java.math.BigInteger.valueOf(now);
            X509v3CertificateBuilder b=new JcaX509v3CertificateBuilder(dn,serial,nb,na,dn,keyPair.getPublic());
            ContentSigner s=new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            cert=new JcaX509CertificateConverter().getCertificate(b.build(s));
        }

        byte[] trim(byte[] b){ if(b.length>1 && b[0]==0) return Arrays.copyOfRange(b,1,b.length); return b; }
        byte[] hexToBytes(String s){
            int len=s.length(); byte[] data=new byte[len/2];
            for(int i=0;i<len;i+=2) data[i/2]=(byte)((Character.digit(s.charAt(i),16)<<4)+Character.digit(s.charAt(i+1),16));
            return data;
        }
    }
}
