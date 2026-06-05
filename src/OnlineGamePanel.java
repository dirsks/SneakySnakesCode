import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * OnlineGamePanel - Multiplayer snake panel (v2).
 * New in v2:
 *  - Snake visuals match offline mode (SkinSystem.drawSegment)
 *  - Skin sent to server on join; received and applied for all players
 *  - Day/Night toggle (N key), music starts on match begin
 *  - Camera: zoom only triggers at length > 20, and large snakes slow down
 *  - Coins spawn in online mode (server sends coin_spawn events)
 *  - Icons loaded from Resources/content/icons/ (no emoji)
 *  - All offline mechanics ported (particles, combo, level-up, food rarity)
 */
public class OnlineGamePanel extends JPanel implements ActionListener, KeyListener,
        MouseListener, MouseMotionListener {

    // -- World constants
    public static final int WORLD_SIZE  = 1024;
    public static final int TILE        = 16;
    public static final int WORLD_TILES = WORLD_SIZE / TILE;

    // -- Screen
    public static final int WIDTH  = 800;
    public static final int HEIGHT = 600;

    // -- Minimap
    private static final int MINI_SIZE   = 150;
    private static final int MINI_MARGIN = 10;
    private static final int MINI_X      = WIDTH  - MINI_SIZE - MINI_MARGIN;
    private static final int MINI_Y      = MINI_MARGIN;

    // -- Icons (loaded from Resources/content/icons/)
    private static final String ICON_PATH = "SneakySnakes/Resources/content/icons/";
    private BufferedImage iconShop, iconNotEnough, iconOwned, iconMoon, iconSun;

    // -- Player colors fallback
    private static final Color[] PLAYER_COLORS = {
        new Color(80,200,255),  new Color(100,255,120),
        new Color(255,180,60),  new Color(255,80,180),
        new Color(180,120,255), new Color(60,220,200),
        new Color(255,255,80),  new Color(255,120,60),
    };

    // -- State
    private String playerName;
    private String serverUrl;
    private Runnable onBack;

    // -- Network
    private WebSocketClient wsClient;
    private volatile boolean connected = false;
    private String statusMsg = "Connecting...";

    // -- Local player
    private int myId = -1;
    private int[] myBody;
    private int myLen = 0;
    private int myDirX = 1, myDirY = 0;
    private int myNextDirX = 1, myNextDirY = 0;
    private boolean myAlive  = true;
    private boolean sprinting = false;

    // -- Online stats
    private int myScore    = 0;
    private int myCoins    = 0;
    private int myCombo    = 1;
    private long myLastFood = 0;
    private int myLevel    = 1;
    private int myXp       = 0;
    private static final long COMBO_WINDOW = 3000;

    // -- Level-up banner
    private float levelUpTimer   = 0f;
    private int   displayedLevel = 1;
    private float comboTimer     = 0f;
    private int   lastCombo      = 1;

    // -- Day/Night
    private boolean nightMode    = false;
    private float   dayNightBlend = 0f;

    // -- All players
    private final ConcurrentHashMap<Integer, PlayerData> players = new ConcurrentHashMap<>();

    // -- Foods & coins
    private final List<int[]> serverFoods  = Collections.synchronizedList(new ArrayList<>());
    private final List<int[]> serverCoins  = Collections.synchronizedList(new ArrayList<>()); // [wx,wy]

    // -- Camera
    //private float camX = WORLD_SIZE/2f, camY = WORLD_SIZE/2f;
    //private float zoom = 1.5f;

    // -- Particles (offline system reused)
    private final ParticleSystem particles = new ParticleSystem();

    // -- Timing
    private javax.swing.Timer gameTimer;
    private long lastTime  = System.currentTimeMillis();
    private long tickCount = 0;
    private int  fpsCounter=0, fps=60;
    private long fpsTimer=0;
    private int  moveAccum=0, moveRate=8;

    // -- Buffer
    private BufferedImage buffer;
    private Graphics2D    bufferG;

    // -- UI
    private boolean deadOverlay   = false;
    private float   deadAlpha     = 0f;
    private boolean backHover     = false;
    private boolean respawnHover  = false;

    private final List<String> eventLog = Collections.synchronizedList(new ArrayList<>());
    private static final int LOG_MAX = 8;

    // =========================================================================
    static class PlayerData {
        int id;
        String name;
        String skinId;
        int[] bodyX, bodyY;
        int length;
        int dirX=1, dirY=0;
        boolean alive = true;
        Color color;
        long lastUpdate;

        PlayerData(int id, String name, String skinId, int colorIdx) {
            this.id = id; this.name = name; this.skinId = skinId;
            this.color = PLAYER_COLORS[colorIdx % PLAYER_COLORS.length];
            this.bodyX = new int[2048]; this.bodyY = new int[2048];
            this.length = 0;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    // =========================================================================
    public OnlineGamePanel(String playerName, String serverUrl, Runnable onBack) {
        this.playerName = playerName;
        this.serverUrl  = serverUrl;
        this.onBack     = onBack;

        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);

        buffer  = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        bufferG = buffer.createGraphics();
        bufferG.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        bufferG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        myBody = new int[4096];
        loadIcons();

        gameTimer = new javax.swing.Timer(16, this);
        gameTimer.start();
    }

    private void loadIcons() {
        iconShop      = loadIcon("skinshop.png");
        iconNotEnough = loadIcon("enough.png");
        iconOwned     = loadIcon("ok.png");
        iconMoon      = loadIcon("moon.png");
        iconSun       = loadIcon("sun.png");
    }

    private BufferedImage loadIcon(String name) {
        try {
            java.io.File f = new java.io.File(ICON_PATH + name);
            if (f.exists()) return javax.imageio.ImageIO.read(f);
        } catch (Exception ignored) {}
        return null;
    }

    public void connect() {
        statusMsg = "Connecting to external server...";
        wsClient = new WebSocketClient(serverUrl, this::onMessage, this::onConnected, this::onDisconnected);
        new Thread(wsClient, "ws-client").start();
    }

    // =========================================================================
    // NETWORK
    // =========================================================================

    void onConnected() {
        connected = true;
        statusMsg = "Joining...";
        String skinId = SkinSystem.current().id;
        send("{\"type\":\"join\",\"name\":\"" + escapeJson(playerName) +
             "\",\"skin\":\"" + escapeJson(skinId) + "\"}");
    }

    void onDisconnected() {
        connected = false;
        statusMsg = "Disconnected from server.";
    }

    void onMessage(String msg) {
        try {
            String type = jsonString(msg, "type");
            if (type == null) return;
            switch (type) {
                case "welcome": {
                    myId = jsonInt(msg, "id");
                    statusMsg = "Connected as #" + myId;
                    int sx = WORLD_TILES/2, sy = WORLD_TILES/2;
                    myLen = 4;
                    for (int i=0;i<myLen;i++) { myBody[i*2]=sx-i; myBody[i*2+1]=sy; }
                    myAlive = true; myScore=0; myCoins=0; myCombo=1; myLevel=1; myXp=0;
                    logEvent("You joined as " + playerName);
                    SoundSystem.startRandomTrack(); // music starts when match begins
                    break;
                }
                case "state":    parseStateUpdate(msg); break;
                case "player_joined": logEvent(jsonString(msg,"name") + " joined"); break;
                case "player_left": {
                    int id = jsonInt(msg,"id");
                    PlayerData pd = players.remove(id);
                    if (pd!=null) logEvent(pd.name + " left");
                    break;
                }
                case "player_died": {
                    int id = jsonInt(msg,"id");
                    String killer = jsonString(msg,"killer");
                    PlayerData pd = players.get(id);
                    if (pd!=null) {
                        pd.alive=false;
                        logEvent(pd.name + (killer!=null&&!killer.isEmpty()?" was killed by "+killer:" died"));
                    }
                    if (id==myId) { myAlive=false; deadOverlay=true; deadAlpha=0f; SoundSystem.stopMusic(); }
                    break;
                }
                case "foods":      parseFoods(msg); break;
                case "coin_spawn": parseCoins(msg); break;
            }
        } catch (Exception ignored) {}
    }

    private void parseStateUpdate(String msg) {
        int ps = msg.indexOf("\"players\":");
        if (ps<0) return;
        int as = msg.indexOf('[', ps);
        if (as<0) return;
        int depth=0, start=-1;
        for (int i=as; i<msg.length(); i++) {
            char c=msg.charAt(i);
            if (c=='{') { if(depth==0) start=i; depth++; }
            else if (c=='}') { depth--; if(depth==0&&start>=0) { parseOnePlayer(msg.substring(start,i+1)); start=-1; } }
        }
    }

    private void parseOnePlayer(String p) {
        int id = jsonInt(p,"id");
        if (id == myId) return;
        String name   = jsonString(p,"name");   if(name==null)  name="Player"+id;
        String skinId = jsonString(p,"skin");   if(skinId==null) skinId="bright_blue";
        boolean alive = jsonBool(p,"alive");

        PlayerData pd = players.get(id);
        if (pd==null) { pd=new PlayerData(id,name,skinId,id); players.put(id,pd); }
        pd.name=name; pd.skinId=skinId; pd.alive=alive; pd.lastUpdate=System.currentTimeMillis();

        int bodyStart = p.indexOf("\"body\":");
        if (bodyStart>=0) {
            int arrS=p.indexOf('[',bodyStart), arrE=p.lastIndexOf(']');
            if (arrS>=0&&arrE>arrS) {
                String bodyStr=p.substring(arrS+1,arrE);
                int seg=0, i=0;
                while (i<bodyStr.length()&&seg<2048) {
                    int bs=bodyStr.indexOf('[',i), be=bodyStr.indexOf(']',bs);
                    if (bs<0||be<0) break;
                    String[] parts=bodyStr.substring(bs+1,be).split(",");
                    if (parts.length>=2) {
                        try { pd.bodyX[seg]=Integer.parseInt(parts[0].trim()); pd.bodyY[seg]=Integer.parseInt(parts[1].trim()); seg++; }
                        catch(NumberFormatException ignored){}
                    }
                    i=be+1;
                }
                pd.length=seg;
            }
        }
        int dirStart=p.indexOf("\"dir\":");
        if (dirStart>=0) {
            int ds=p.indexOf('[',dirStart),de=p.indexOf(']',ds);
            if (ds>=0&&de>ds) {
                String[] parts=p.substring(ds+1,de).split(",");
                if (parts.length>=2) try { pd.dirX=Integer.parseInt(parts[0].trim()); pd.dirY=Integer.parseInt(parts[1].trim()); } catch(NumberFormatException ignored){}
            }
        }
        if (id==myId && pd.length>0) { myLen=pd.length; for(int i=0;i<myLen;i++){myBody[i*2]=pd.bodyX[i];myBody[i*2+1]=pd.bodyY[i];} }
    }

    private void parseFoods(String msg) {
        serverFoods.clear();
        int fs=msg.indexOf("\"foods\":"); if(fs<0) return;
        int as=msg.indexOf('[',fs), ae=msg.lastIndexOf(']');
        if (as<0||ae<=as) return;
        String arr=msg.substring(as+1,ae); int i=0;
        while(i<arr.length()) {
            int bs=arr.indexOf('[',i),be=arr.indexOf(']',bs); if(bs<0||be<0) break;
            String[] parts=arr.substring(bs+1,be).split(",");
            if (parts.length>=3) try { serverFoods.add(new int[]{Integer.parseInt(parts[0].trim()),Integer.parseInt(parts[1].trim()),Integer.parseInt(parts[2].trim())}); } catch(NumberFormatException ignored){}
            i=be+1;
        }
    }

    private void parseCoins(String msg) {
        // {type:"coin_spawn", coins:[[x,y],...]}
        serverCoins.clear();
        int fs=msg.indexOf("\"coins\":"); if(fs<0) return;
        int as=msg.indexOf('[',fs), ae=msg.lastIndexOf(']');
        if (as<0||ae<=as) return;
        String arr=msg.substring(as+1,ae); int i=0;
        while(i<arr.length()) {
            int bs=arr.indexOf('[',i),be=arr.indexOf(']',bs); if(bs<0||be<0) break;
            String[] parts=arr.substring(bs+1,be).split(",");
            if (parts.length>=2) try { serverCoins.add(new int[]{Integer.parseInt(parts[0].trim()),Integer.parseInt(parts[1].trim())}); } catch(NumberFormatException ignored){}
            i=be+1;
        }
    }

    private void send(String json) { if (wsClient!=null) wsClient.send(json); }

    private void logEvent(String msg) {
        synchronized(eventLog) { eventLog.add(0,msg); if(eventLog.size()>LOG_MAX) eventLog.remove(eventLog.size()-1); }
    }

    // =========================================================================
    // GAME LOOP
    // =========================================================================

    @Override
    public void actionPerformed(ActionEvent e) {
        long now=System.currentTimeMillis(), dt=now-lastTime; lastTime=now;
        tickCount++; fpsCounter++; fpsTimer+=dt;
        if (fpsTimer>=1000) { fps=fpsCounter; fpsCounter=0; fpsTimer=0; }
        update(dt);
        drawToBuffer();
        repaint();
    }

    private void update(long dt) {
        particles.update();
        float targetBlend = nightMode ? 1f : 0f;
        dayNightBlend += (targetBlend - dayNightBlend) * 0.05f;
        if (levelUpTimer>0) levelUpTimer -= dt/1000f;
        if (comboTimer>0)   comboTimer   -= dt/1000f;

        if (deadOverlay) { deadAlpha=Math.min(1f,deadAlpha+0.03f); return; }
        if (!connected||!myAlive) return;

        // Speed reduction for large snakes
        int baseRate = 8;
        int sizeBonus = Math.max(0, (myLen - 20) / 5); // slower past length 20
        int effectiveRate = Math.min(baseRate + sizeBonus, 14);
        int currentRate = sprinting ? Math.max(3, effectiveRate - 3) : effectiveRate;

        moveAccum++;
        if (moveAccum >= currentRate) { moveAccum=0; localMoveSnake(); }

        // Camera: zoom only kicks in past length 20
        /* if (myLen>0) {
            float headWX = myBody[0]*TILE + TILE/2f;
            float headWY = myBody[1]*TILE + TILE/2f;
            float targetZoom;
            if (myLen <= 20) {
                targetZoom = 1.0f; // no zoom change while small
            } else {
                targetZoom = 1.0f + (myLen - 20) * 0.04f;
                targetZoom = Math.min(targetZoom, 4.0f);
            }
            zoom += (targetZoom - zoom) * 0.05f;
            camX += (headWX - camX) * 0.12f;
            camY += (headWY - camY) * 0.12f;
        } */
    }

    private void localMoveSnake() {
        myDirX = myNextDirX; myDirY = myNextDirY;
        int newHX = myBody[0] + myDirX, newHY = myBody[1] + myDirY;

        // Borda — cliente decide
        if (newHX < 0 || newHX >= WORLD_TILES || newHY < 0 || newHY >= WORLD_TILES) {
            send("{\"type\":\"die\",\"reason\":\"border\"}");
            myAlive = false; deadOverlay = true; return;
        }

        // Self-collision — cliente decide
        for (int i = 1; i < myLen - 1; i++) {
            if (myBody[i*2] == newHX && myBody[i*2+1] == newHY) {
                send("{\"type\":\"die\",\"reason\":\"self\"}");
                myAlive = false; deadOverlay = true; return;
            }
        }

        // Colisão com outros jogadores — cliente decide localmente
        for (PlayerData pd : players.values()) {
            if (!pd.alive) continue;
            for (int i = 0; i < pd.length; i++) {
                if (pd.bodyX[i] == newHX && pd.bodyY[i] == newHY) {
                    send("{\"type\":\"die\",\"reason\":\"player\"}");
                    myAlive = false; deadOverlay = true; return;
                }
            }
        }
        boolean ateFood = false;
        int eatenRarity = 0;
        synchronized(serverFoods) {
            for (int fi = serverFoods.size() - 1; fi >= 0; fi--) {
                int[] food = serverFoods.get(fi);
                if (food[0] == newHX && food[1] == newHY) {
                    eatenRarity = food[2];
                    serverFoods.remove(fi);
                    ateFood = true;
                    break;
                }
            }
        }
        if (ateFood) {
            int grow = new int[]{1,3,8,20}[Math.min(eatenRarity,3)];
            System.arraycopy(myBody, 0, myBody, 2, myLen * 2);
            myBody[0] = newHX; myBody[1] = newHY;
            myLen++;
            for (int g = 1; g < grow && myLen * 2 < myBody.length - 2; g++) {
                System.arraycopy(myBody, 0, myBody, 2, myLen * 2);
                myBody[0] = newHX; myBody[1] = newHY;
                myLen++;
            }
            onFoodEaten(eatenRarity, newHX, newHY);
        } else {
            System.arraycopy(myBody, 0, myBody, 2, myLen * 2 - 2);
            myBody[0] = newHX; myBody[1] = newHY;
        }
        SkinSystem.Skin skin = SkinSystem.current();
        particles.spawnTrail(newHX * TILE + TILE/2f, newHY * TILE + TILE/2f, skin.glow);
        synchronized(serverCoins) {
            for (int ci = serverCoins.size() - 1; ci >= 0; ci--) {
                int[] coin = serverCoins.get(ci);
                if (coin[0] == newHX && coin[1] == newHY) {
                    myCoins++;
                    serverCoins.remove(ci);
                    particles.spawnFoodCollect(newHX*TILE+TILE/2f, newHY*TILE+TILE/2f, new Color(255,215,0), 8);
                    SoundSystem.play("eat");
                    send("{\"type\":\"coin_collect\",\"x\":" + newHX + ",\"y\":" + newHY + "}");
                }
            }
        }
        send("{\"type\":\"move\",\"dir\":[" + myDirX + "," + myDirY + "]" +
            ",\"head\":[" + newHX + "," + newHY + "]" +
            ",\"body\":" + bodyToJson() +
            ",\"sprint\":" + sprinting + "}");
    }
    private String bodyToJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < myLen; i++) {
            if (i > 0) sb.append(",");
            sb.append("[").append(myBody[i*2]).append(",").append(myBody[i*2+1]).append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    private void onFoodEaten(int rarity, int fx, int fy) {
        SoundSystem.play("eat");
        long now=System.currentTimeMillis();
        if ((now-myLastFood)<=COMBO_WINDOW) myCombo=Math.min(myCombo+1,10); else myCombo=1;
        myLastFood=now;
        if (myCombo>1) { SoundSystem.play("combo"); lastCombo=myCombo; comboTimer=1.8f; particles.spawnCombo(fx*TILE+TILE/2f,fy*TILE+TILE/2f,myCombo); }

        int[] scoreGains = {10,30,80,200};
        int[] xpGains = {5,15,40,100};

        myScore += scoreGains[Math.min(rarity,3)];
        myXp += xpGains[Math.min(rarity,3)];
        GameState.FoodRarity fr = GameState.FoodRarity.values()[Math.min(rarity,3)];
        particles.spawnFoodCollect(fx*TILE+TILE/2f,fy*TILE+TILE/2f,fr.glowColor,fr.particleCount);
        checkLevelUp();
    }

    private void checkLevelUp() {
        while (myLevel<8) {
            int need=GameState.xpForNextLevel(myLevel);
            if (myXp<need) break;
            myXp-=need; myLevel++; displayedLevel=myLevel; levelUpTimer=2.5f;
            SoundSystem.play("levelup"); particles.spawnLevelUp(WIDTH/2f,HEIGHT/2f);
        }
    }

    // =========================================================================
    // RENDERING
    // =========================================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(buffer,0,0,getWidth(),getHeight(),null);
    }

    private void drawToBuffer() {
        Graphics2D g=bufferG;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        if (!connected&&!deadOverlay) { drawConnecting(g); return; }
        drawWorldBackground(g);
        drawCoins(g);
        drawFoods(g);
        drawAllSnakes(g);
        drawBorderVoid(g);
        particles.draw(g);  // draw particles in world space (no transform needed, particles store screen coords)
        drawHUD(g);
        drawMinimap(g);
        drawEventLog(g);
        if (levelUpTimer>0)         drawLevelUpBanner(g);
        if (comboTimer>0&&lastCombo>1) drawComboBanner(g);
        if (deadOverlay)            drawDeadOverlay(g);
    }

    private void drawConnecting(Graphics2D g) {
        g.setColor(new Color(10,12,25)); g.fillRect(0,0,WIDTH,HEIGHT);
        g.setFont(new Font("Arial",Font.BOLD,28)); g.setColor(new Color(80,200,255));
        String s=statusMsg; FontMetrics fm=g.getFontMetrics();
        g.drawString(s,(WIDTH-fm.stringWidth(s))/2,HEIGHT/2);
        String dots=".".repeat((int)(tickCount/20%4));
        g.setFont(new Font("Arial",Font.PLAIN,20)); g.setColor(new Color(120,180,255));
        g.drawString(dots,WIDTH/2,HEIGHT/2+30);
        drawButton(g,WIDTH/2-60,HEIGHT/2+60,120,36,"Back",backHover);
    }

    //private int wx2sx(float wx) { return (int)((wx-camX)/zoom+WIDTH/2f); }
    //private int wy2sy(float wy) { return (int)((wy-camY)/zoom+HEIGHT/2f); }
    //private int wsz(float w)    { return Math.max(1,(int)(w/zoom)); }
    //private boolean onScreen(float wx,float wy,float m) { int sx=wx2sx(wx),sy=wy2sy(wy); return sx>-m&&sx<WIDTH+m&&sy>-m&&sy<HEIGHT+m; }
    private int wx2sx(float wx) {
        if(myLen<=0)return (int)wx;
        float hx=myBody[0]*TILE+TILE/2f;
        return (int)(wx-hx+WIDTH/2f);
    }

    private int wy2sy(float wy) {
        if(myLen<=0)return (int)wy;
        float hy=myBody[0+1]*TILE+TILE/2f;
        return (int)(wy-hy+HEIGHT/2f);
    }

    private int wsz(float w) {
        return (int)w;
    }

    private boolean onScreen(float wx,float wy,float m) {
        int sx=wx2sx(wx);
        int sy=wy2sy(wy);
        return sx>-m&&sx<WIDTH+m&&sy>-m&&sy<HEIGHT+m;
    }

    private void drawWorldBackground(Graphics2D g) {
        // Day/Night blend for background
        Color dayBg   = new Color(22,28,42);
        Color nightBg = new Color(8,8,18);
        Color bg = blendColor(dayBg, nightBg, dayNightBlend);
        g.setColor(Color.BLACK); g.fillRect(0,0,WIDTH,HEIGHT);
        int wx0=wx2sx(0),wy0=wy2sy(0),wxE=wx2sx(WORLD_SIZE),wyE=wy2sy(WORLD_SIZE);
        g.setColor(bg); g.fillRect(wx0,wy0,wxE-wx0,wyE-wy0);
        float a = nightMode ? 0.18f : 0.07f;
        Color gc = nightMode ? new Color(0,255,150,(int)(a*255)) : new Color(255,255,255,(int)(a*255));
        g.setColor(gc);
        int tileS=wsz(TILE);
        if (tileS>=4) {
            for (int tx=0;tx<=WORLD_TILES;tx++) { int sx=wx2sx(tx*TILE); g.drawLine(sx,wy0,sx,wyE); }
            for (int ty=0;ty<=WORLD_TILES;ty++) { int sy=wy2sy(ty*TILE); g.drawLine(wx0,sy,wxE,sy); }
        }
    }

    private void drawBorderVoid(Graphics2D g) {
        int wx0=wx2sx(0),wy0=wy2sy(0),wxE=wx2sx(WORLD_SIZE),wyE=wy2sy(WORLD_SIZE);
        int glow=Math.max(4,wsz(20));
        GradientPaint left =new GradientPaint(wx0,0,new Color(255,40,40,140),wx0+glow,0,new Color(0,0,0,0));
        GradientPaint right=new GradientPaint(wxE,0,new Color(255,40,40,140),wxE-glow,0,new Color(0,0,0,0));
        GradientPaint top  =new GradientPaint(0,wy0,new Color(255,40,40,140),0,wy0+glow,new Color(0,0,0,0));
        GradientPaint bot  =new GradientPaint(0,wyE,new Color(255,40,40,140),0,wyE-glow,new Color(0,0,0,0));
        g.setPaint(left);  g.fillRect(wx0,wy0,glow,wyE-wy0);
        g.setPaint(right); g.fillRect(wxE-glow,wy0,glow,wyE-wy0);
        g.setPaint(top);   g.fillRect(wx0,wy0,wxE-wx0,glow);
        g.setPaint(bot);   g.fillRect(wx0,wyE-glow,wxE-wx0,glow);
        g.setPaint(null);
    }

    private void drawCoins(Graphics2D g) {
        int sz=wsz(TILE);
        synchronized(serverCoins) {
            for (int[] coin : serverCoins) {
                float fx=coin[0]*TILE+TILE/2f, fy=coin[1]*TILE+TILE/2f;
                if (!onScreen(fx,fy,sz*2)) continue;
                int sx=wx2sx(fx), sy=wy2sy(fy);
                int r=Math.max(4,sz/2);
                g.setColor(new Color(255,215,0,60)); g.fillOval(sx-r-3,sy-r-3,r*2+6,r*2+6);
                g.setColor(new Color(255,215,0));    g.fillOval(sx-r,sy-r,r*2,r*2);
                g.setColor(new Color(255,255,180,150)); g.fillOval(sx-r/2,sy-r/2,r/2,r/2);
                // coin icon or fallback text
                g.setFont(new Font("Arial",Font.BOLD,Math.max(7,sz/2)));
                g.setColor(new Color(180,120,0));
                g.drawString("$",sx-2,sy+3);
            }
        }
    }

    private void drawFoods(Graphics2D g) {
        int sz=wsz(TILE);
        synchronized(serverFoods) {
            for (int[] food : serverFoods) {
                float fx=food[0]*TILE+TILE/2f,fy=food[1]*TILE+TILE/2f;
                if (!onScreen(fx,fy,sz*2)) continue;
                int sx=wx2sx(fx),sy=wy2sy(fy);
                GameState.FoodRarity r=GameState.FoodRarity.values()[Math.min(food[2],3)];
                int radius=Math.max(3,sz/2);
                g.setColor(new Color(r.glowColor.getRed(),r.glowColor.getGreen(),r.glowColor.getBlue(),60));
                g.fillOval(sx-radius-3,sy-radius-3,radius*2+6,radius*2+6);
                g.setColor(r.color); g.fillOval(sx-radius,sy-radius,radius*2,radius*2);
                g.setColor(new Color(255,255,255,100)); g.fillOval(sx-radius/2,sy-radius/2,radius/2,radius/3);
            }
        }
    }

    private void drawAllSnakes(Graphics2D g) {
        for (PlayerData pd : players.values()) {
            if (pd.id==myId||!pd.alive||pd.length==0) continue;
            drawOtherSnake(g,pd);
        }
        if (myAlive&&myLen>0) drawLocalSnake(g);
    }

    private void drawOtherSnake(Graphics2D g, PlayerData pd) {
        SkinSystem.Skin skin = SkinSystem.findById(pd.skinId);
        if (skin==null) skin = SkinSystem.current(); // fallback
        int sz=wsz(TILE); if(sz<2) sz=2;
        for (int i=pd.length-1;i>=0;i--) {
            float wx=pd.bodyX[i]*TILE, wy=pd.bodyY[i]*TILE;
            if (!onScreen(wx,wy,sz*2)) continue;
            int sx=wx2sx(wx),sy=wy2sy(wy);
            SkinSystem.drawSegment(g,skin,sx,sy,sz,i==0,tickCount,i);
            if (i==0) drawEyes(g,sx,sy,sz,pd.dirX,pd.dirY);
        }
        // name tag
        if (pd.length>0) {
            int sx=wx2sx(pd.bodyX[0]*TILE+TILE/2f),sy=wy2sy(pd.bodyY[0]*TILE);
            drawNameTag(g,pd.name+"["+pd.length+"]",sx,sy,false);
        }
    }

    private void drawLocalSnake(Graphics2D g) {
        SkinSystem.Skin skin=SkinSystem.current();
        int sz=wsz(TILE); if(sz<2) sz=2;
        for (int i=myLen-1;i>=0;i--) {
            float wx=myBody[i*2]*TILE, wy=myBody[i*2+1]*TILE;
            if (!onScreen(wx,wy,sz*2)) continue;
            int sx=wx2sx(wx),sy=wy2sy(wy);
            SkinSystem.drawSegment(g,skin,sx,sy,sz,i==0,tickCount,i);
            if (i==0) drawEyes(g,sx,sy,sz,myDirX,myDirY);
        }
        if (myLen>0) {
            int sx=wx2sx(myBody[0]*TILE+TILE/2f),sy=wy2sy(myBody[1]*TILE);
            drawNameTag(g,playerName+"(you)["+myLen+"]",sx,sy,true);
        }
    }

    private void drawEyes(Graphics2D g,int sx,int sy,int sz,int dx,int dy) {
        int off=sz/4, es=Math.max(2,sz/5);
        int ex1,ey1,ex2,ey2;
        if      (dx==1)  { ex1=sx+sz-off-2;ey1=sy+off;    ex2=sx+sz-off-2;ey2=sy+sz-off*2; }
        else if (dx==-1) { ex1=sx+2;        ey1=sy+off;    ex2=sx+2;        ey2=sy+sz-off*2; }
        else if (dy==-1) { ex1=sx+off;      ey1=sy+2;      ex2=sx+sz-off*2; ey2=sy+2; }
        else             { ex1=sx+off;      ey1=sy+sz-off-2;ex2=sx+sz-off*2;ey2=sy+sz-off-2; }
        g.setColor(Color.WHITE); g.fillOval(ex1-es/2,ey1-es/2,es,es); g.fillOval(ex2-es/2,ey2-es/2,es,es);
        g.setColor(Color.BLACK); g.fillOval(ex1-es/4,ey1-es/4,es/2,es/2); g.fillOval(ex2-es/4,ey2-es/4,es/2,es/2);
    }

    private void drawNameTag(Graphics2D g, String tag, int sx, int sy, boolean isLocal) {
        int nsz=Math.max(9,wsz(TILE)/2+5);
        g.setFont(new Font("Arial",Font.BOLD,nsz));
        FontMetrics fm=g.getFontMetrics();
        int tw=fm.stringWidth(tag);
        g.setColor(new Color(0,0,0,140)); g.fillRoundRect(sx-tw/2-3,sy-nsz-8,tw+6,nsz+4,6,6);
        g.setColor(isLocal?new Color(180,255,180):new Color(255,255,255));
        g.drawString(tag,sx-tw/2,sy-nsz+2);
    }

    private void drawHUD(Graphics2D g) {
        g.setColor(new Color(10,14,30,200)); g.fillRoundRect(8,8,195,165,12,12);
        g.setColor(new Color(100,160,255,50)); g.drawRoundRect(8,8,195,165,12,12);
        g.setFont(new Font("Arial",Font.BOLD,13));
        String[] lines={
            "Score:   "+myScore,
            "Level:   "+myLevel,
            "Combo:   x"+myCombo,
            "Coins:   "+myCoins,
            "Length:  "+myLen,
            "Players: "+(players.size()+1),
            "Sprint:  "+(sprinting?"ON":"OFF"),
            "FPS:     "+fps,
        };
        for (int i=0;i<lines.length;i++) {
            g.setColor(i==0?Color.WHITE:new Color(180,220,255));
            g.drawString(lines[i],18,28+i*19);
        }

        // Day/Night icon
        BufferedImage modeIcon = nightMode ? iconMoon : iconSun;
        if (modeIcon!=null) g.drawImage(modeIcon,WIDTH-36,10,24,24,null);
        else { g.setFont(new Font("Arial",Font.PLAIN,11)); g.setColor(new Color(140,200,255,160)); g.drawString(nightMode?"NIGHT":"DAY",WIDTH-50,22); }

        // XP bar
        drawXpBar(g);

        if (!statusMsg.isEmpty()) {
            g.setFont(new Font("Arial",Font.PLAIN,11));
            g.setColor(connected?new Color(100,255,120,200):new Color(255,120,80,200));
            g.drawString(statusMsg,8,HEIGHT-8);
        }
    }

    private void drawXpBar(Graphics2D g) {
        int need=GameState.xpForNextLevel(myLevel);
        float pct=(need==Integer.MAX_VALUE)?1f:(float)myXp/need;
        int bx=8,by=HEIGHT-20,bw=195,bh=12;
        g.setColor(new Color(20,20,40,180)); g.fillRoundRect(bx,by,bw,bh,6,6);
        g.setColor(new Color(80,200,255));   g.fillRoundRect(bx,by,(int)(bw*pct),bh,6,6);
        g.setColor(new Color(100,180,255,100)); g.drawRoundRect(bx,by,bw,bh,6,6);
        g.setFont(new Font("Arial",Font.BOLD,9)); g.setColor(Color.WHITE); g.drawString("XP",bx+4,by+9);
    }

    private void drawMinimap(Graphics2D g) {
        g.setColor(new Color(8,10,20,210)); g.fillRoundRect(MINI_X,MINI_Y,MINI_SIZE,MINI_SIZE,10,10);
        g.setColor(new Color(80,140,255,80)); g.drawRoundRect(MINI_X,MINI_Y,MINI_SIZE,MINI_SIZE,10,10);
        float scale=(float)MINI_SIZE/WORLD_SIZE;
        for (PlayerData pd : players.values()) {
            if (!pd.alive||pd.length==0) continue;
            float mx=MINI_X+pd.bodyX[0]*TILE*scale, my=MINI_Y+pd.bodyY[0]*TILE*scale;
            g.setColor(pd.id==myId?new Color(255,255,80):pd.color);
            g.fillOval((int)mx-3,(int)my-3,6,6);
        }
        if (myLen>0) {
            float mx=MINI_X+myBody[0]*TILE*scale, my=MINI_Y+myBody[1]*TILE*scale;
            g.setColor(new Color(255,255,80)); g.fillOval((int)mx-4,(int)my-4,8,8);
            g.setColor(Color.WHITE); g.drawOval((int)mx-4,(int)my-4,8,8);
        }
        g.setFont(new Font("Arial",Font.BOLD,9)); g.setColor(new Color(180,220,255,180));
        g.drawString("MAP",MINI_X+MINI_SIZE/2-8,MINI_Y+MINI_SIZE+12);
    }

    private void drawEventLog(Graphics2D g) {
        g.setFont(new Font("Arial",Font.PLAIN,11));
        synchronized(eventLog) {
            int n=Math.min(eventLog.size(),LOG_MAX);
            for (int i=0;i<n;i++) {
                float alpha=1f-(float)i/LOG_MAX;
                g.setColor(new Color(200,230,255,(int)(alpha*200)));
                g.drawString(eventLog.get(i),MINI_X,MINI_Y+MINI_SIZE+26+i*14);
            }
        }
    }

    private void drawLevelUpBanner(Graphics2D g) {
        float t=levelUpTimer/2.5f, alpha=t>0.8f?(1f-t)/0.2f:(t<0.2f?t/0.2f:1f);
        int bw=280,bh=70,bx=(WIDTH-bw)/2,by=HEIGHT/2-120;
        g.setColor(new Color(255,215,0,(int)(alpha*30))); g.fillRoundRect(bx-8,by-8,bw+16,bh+16,20,20);
        g.setColor(new Color(20,20,40,(int)(alpha*200))); g.fillRoundRect(bx,by,bw,bh,16,16);
        g.setColor(new Color(255,215,0,(int)(alpha*180))); g.drawRoundRect(bx,by,bw,bh,16,16);
        g.setFont(new Font("Arial",Font.BOLD,26)); g.setColor(new Color(255,215,0,(int)(alpha*255)));
        String lv="LEVEL UP! -> "+displayedLevel;
        FontMetrics fm=g.getFontMetrics(); g.drawString(lv,(WIDTH-fm.stringWidth(lv))/2,by+bh/2+9);
    }

    private void drawComboBanner(Graphics2D g) {
        float alpha=comboTimer/1.8f;
        int bw=180,bh=48,bx=(WIDTH-bw)/2,by=80;
        g.setColor(new Color(255,120,0,(int)(alpha*180))); g.fillRoundRect(bx,by,bw,bh,12,12);
        g.setColor(new Color(255,200,50,(int)(alpha*200))); g.drawRoundRect(bx,by,bw,bh,12,12);
        g.setFont(new Font("Arial",Font.BOLD,22)); g.setColor(new Color(255,240,100,(int)(alpha*255)));
        String ct="COMBO x"+lastCombo+"!"; FontMetrics fm=g.getFontMetrics();
        g.drawString(ct,bx+(bw-fm.stringWidth(ct))/2,by+30);
    }

    private void drawDeadOverlay(Graphics2D g) {
        g.setColor(new Color(0,0,0,(int)(deadAlpha*180))); g.fillRect(0,0,WIDTH,HEIGHT);
        if (deadAlpha<0.5f) return;
        int pw=340,ph=200,px=(WIDTH-pw)/2,py=(HEIGHT-ph)/2;
        g.setColor(new Color(12,10,28,220)); g.fillRoundRect(px,py,pw,ph,16,16);
        g.setColor(new Color(255,60,60,160)); g.drawRoundRect(px,py,pw,ph,16,16);
        g.setFont(new Font("Arial",Font.BOLD,44)); g.setColor(new Color(255,80,80));
        String go="GAME OVER"; FontMetrics fm=g.getFontMetrics();
        g.drawString(go,px+(pw-fm.stringWidth(go))/2,py+55);
        g.setFont(new Font("Arial",Font.PLAIN,15)); g.setColor(new Color(180,210,255));
        String info="Length: "+myLen+"  Score: "+myScore+"  Coins: +"+myCoins;
        fm=g.getFontMetrics(); g.drawString(info,px+(pw-fm.stringWidth(info))/2,py+85);
        drawButton(g,px+pw/2-120,py+120,110,38,"Respawn",respawnHover);
        drawButton(g,px+pw/2+10, py+120,110,38,"Menu",   backHover);
    }

    private void drawButton(Graphics2D g,int x,int y,int w,int h,String label,boolean hovered) {
        Color base=hovered?new Color(80,180,255):new Color(50,130,200);
        g.setColor(new Color(0,0,0,80)); g.fillRoundRect(x+2,y+2,w,h,12,12);
        g.setColor(base); g.fillRoundRect(x,y,w,h,12,12);
        g.setColor(new Color(255,255,255,60)); g.drawRoundRect(x,y,w,h,12,12);
        g.setFont(new Font("Arial",Font.BOLD,15)); g.setColor(Color.WHITE);
        FontMetrics fm=g.getFontMetrics(); g.drawString(label,x+(w-fm.stringWidth(label))/2,y+h/2+6);
    }

    private Color blendColor(Color a, Color b, float t) {
        t=Math.max(0,Math.min(1,t));
        return new Color((int)(a.getRed()+(b.getRed()-a.getRed())*t),(int)(a.getGreen()+(b.getGreen()-a.getGreen())*t),(int)(a.getBlue()+(b.getBlue()-a.getBlue())*t));
    }

    // =========================================================================
    // INPUT
    // =========================================================================

    @Override
    public void keyPressed(KeyEvent e) {
        int k=e.getKeyCode();
        if (k==KeyEvent.VK_N) { nightMode=!nightMode; return; }
        if (k==KeyEvent.VK_ESCAPE) { disconnect(); onBack.run(); return; }
        if (!myAlive||deadOverlay) return;
        if ((k==KeyEvent.VK_W||k==KeyEvent.VK_UP)    &&myDirY!=1)  {myNextDirX=0; myNextDirY=-1;}
        if ((k==KeyEvent.VK_S||k==KeyEvent.VK_DOWN)  &&myDirY!=-1) {myNextDirX=0; myNextDirY=1; }
        if ((k==KeyEvent.VK_A||k==KeyEvent.VK_LEFT)  &&myDirX!=1)  {myNextDirX=-1;myNextDirY=0; }
        if ((k==KeyEvent.VK_D||k==KeyEvent.VK_RIGHT) &&myDirX!=-1) {myNextDirX=1; myNextDirY=0; }
    }

    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    @Override public void mousePressed(MouseEvent e)  { if(e.getButton()==MouseEvent.BUTTON1) sprinting=true; }
    @Override public void mouseReleased(MouseEvent e) { if(e.getButton()==MouseEvent.BUTTON1) sprinting=false; }

    @Override
    public void mouseClicked(MouseEvent e) {
        int mx=(int)(e.getX()*(float)WIDTH/getWidth()), my=(int)(e.getY()*(float)HEIGHT/getHeight());
        if (deadOverlay) {
            int px=(WIDTH-340)/2,py=(HEIGHT-200)/2;
            if (inRect(mx,my,px+340/2-120,py+120,110,38)) { respawn(); return; }
            if (inRect(mx,my,px+340/2+10, py+120,110,38)) { disconnect(); onBack.run(); return; }
        }
        if (!connected&&inRect(mx,my,WIDTH/2-60,HEIGHT/2+60,120,36)) { disconnect(); onBack.run(); }
    }

    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e)  { backHover=respawnHover=false; }

    @Override
    public void mouseMoved(MouseEvent e) {
        int mx=(int)(e.getX()*(float)WIDTH/getWidth()), my=(int)(e.getY()*(float)HEIGHT/getHeight());
        if (deadOverlay) {
            int px=(WIDTH-340)/2,py=(HEIGHT-200)/2;
            respawnHover=inRect(mx,my,px+340/2-120,py+120,110,38);
            backHover   =inRect(mx,my,px+340/2+10, py+120,110,38);
        } else backHover=!connected&&inRect(mx,my,WIDTH/2-60,HEIGHT/2+60,120,36);
    }

    @Override public void mouseDragged(MouseEvent e) {}
    private boolean inRect(int mx,int my,int x,int y,int w,int h) { return mx>=x&&mx<=x+w&&my>=y&&my<=y+h; }

    private void respawn() {
        myLen=4; myDirX=1; myDirY=0; myNextDirX=1; myNextDirY=0;
        myScore=0; myXp=0; myLevel=1; myCombo=1; myCoins=0;
        int sx=WORLD_TILES/2+(int)(Math.random()*20-10), sy=WORLD_TILES/2+(int)(Math.random()*20-10);
        for (int i=0;i<myLen;i++) { myBody[i*2]=sx-i; myBody[i*2+1]=sy; }
        myAlive=true; deadOverlay=false; deadAlpha=0;
        send("{\"type\":\"respawn\",\"head\":["+sx+","+sy+"]}");
        SoundSystem.startRandomTrack();
        logEvent(playerName+" respawned");
    }

    private void disconnect() {
        gameTimer.stop();
        SoundSystem.stopMusic();
        if (wsClient!=null) wsClient.close();
        connected=false;
    }

    // =========================================================================
    // JSON HELPERS
    // =========================================================================
    private static String jsonString(String json,String key) { String k="\""+key+"\":\""; int i=json.indexOf(k); if(i<0)return null; int s=i+k.length(),e=json.indexOf('"',s); if(e<0)return null; return json.substring(s,e); }
    private static int    jsonInt(String json,String key)    { String k="\""+key+"\":";   int i=json.indexOf(k); if(i<0)return -1;  int s=i+k.length(),e=s; while(e<json.length()&&(Character.isDigit(json.charAt(e))||json.charAt(e)=='-'))e++; try{return Integer.parseInt(json.substring(s,e));}catch(NumberFormatException ex){return -1;} }
    private static boolean jsonBool(String json,String key)  { String k="\""+key+"\":";   int i=json.indexOf(k); if(i<0)return false; return json.startsWith("true",i+k.length()); }
    private static String escapeJson(String s) { return s.replace("\\","\\\\").replace("\"","\\\""); }
}