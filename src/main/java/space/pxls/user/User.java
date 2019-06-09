package space.pxls.user;

import io.undertow.websockets.core.WebSocketChannel;
import space.pxls.App;
import space.pxls.server.Badge;
import space.pxls.server.ClientUndo;
import space.pxls.server.ServerChatBan;
import space.pxls.util.RateLimitFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class User {
    private int id;
    private int stacked = 0;
    private String name;
    private String login;
    private String useragent;

    private Role role = Role.USER;
    private boolean overrideCooldown;
    private boolean flaggedForCaptcha = true;
    private boolean justShowedCaptcha;
    private boolean lastPlaceWasStack = false;
    private boolean placingLock = false;
    private boolean isPermaChatbanned = false;
    private long cooldownExpiry;
    private long lastPixelTime = 0;
    private long lastUndoTime = 0;
    private long initialAuthTime = 0L;

    // 0 = not banned
    private long banExpiryTime;
    private long chatbanExpiryTime;

    private Set<WebSocketChannel> connections = new HashSet<>();

    public User(int id, int stacked, String name, String login, long cooldownExpiry, Role role, long banExpiryTime, boolean isPermaChatbanned, long chatbanExpiryTime) {
        this.id = id;
        this.stacked = stacked;
        this.name = name;
        this.login = login;
        this.cooldownExpiry = cooldownExpiry;
        this.role = role;
        this.banExpiryTime = banExpiryTime;
        this.isPermaChatbanned = isPermaChatbanned;
        this.chatbanExpiryTime = chatbanExpiryTime;
    }

    public int getId() {
        return id;
    }

    public boolean canPlace() {
        if (role.greaterEqual(Role.MODERATOR) && overrideCooldown) return true;
        if (!role.greaterEqual(Role.USER) && role != Role.SHADOWBANNED) return false; // shadowbanned seems to be able to place
        return cooldownExpiry < System.currentTimeMillis();
    }

    public boolean undoWindowPassed() {
        return lastPixelTime + App.getConfig().getDuration("undo.window", TimeUnit.MILLISECONDS) < System.currentTimeMillis();
    }

    public boolean canUndo() {
        return canUndo(true);
    }
    public boolean canUndo(boolean hitBucket) {
        int rem = RateLimitFactory.getTimeRemaining(ClientUndo.class, String.valueOf(this.id), hitBucket);
        return rem == 0;
    }

    public void setLastPixelTime() {
        lastPixelTime = System.currentTimeMillis();
    }

    public long getLastPixelTime() {
        return this.lastPixelTime;
    }

    public void setLastUndoTime() {
        lastUndoTime = System.currentTimeMillis();
    }

    public float getRemainingCooldown() {
        if (role.greaterEqual(Role.MODERATOR) && overrideCooldown) return 0;

        return Math.max(0, cooldownExpiry - System.currentTimeMillis()) / 1000f;
    }

    public boolean updateCaptchaFlagPrePlace() {
        if (role.greaterThan(Role.USER)) {
            flaggedForCaptcha = false;
            return false;
        }

        if (flaggedForCaptcha) return true;

        // Don't show captcha if we *just* had one and haven't had the chance to place yet
        if (justShowedCaptcha) {
            flaggedForCaptcha = false;
            justShowedCaptcha = false;
            return false;
        }

        int captchaThreshold = App.getConfig().getInt("captcha.threshold");
        if (Math.random() < (1f / captchaThreshold)) {
            flaggedForCaptcha = true;
        }

        return flaggedForCaptcha;
    }

    public void setOverrideCooldown(boolean overrideCooldown) {
        this.overrideCooldown = overrideCooldown;
        if (role.lessThan(Role.MODERATOR)) this.overrideCooldown = false;
    }

    public Role getRole() {
        return role;
    }

    public String getLogin() {
        return login;
    }

    private void sendUserData() {
        for (WebSocketChannel ch : connections) {
            App.getServer().getPacketHandler().userdata(ch, this);
        }
    }

    public void setRole(Role role) {
        setRole(role, false);
    }
    public void setRole(Role role, boolean skipSendUserData) {
        this.role = role;
        App.getDatabase().setUserRole(this, role);
        if (!skipSendUserData && role != Role.SHADOWBANNED) {
            sendUserData();
        }
    }

    public String getBanReason() {
        return App.getDatabase().getUserBanReason(this.id);
    }

    public void setBanReason(String reason) {
        App.getDatabase().updateBanReason(this, reason);
    }

    public void setCooldown(int seconds) {
        cooldownExpiry = System.currentTimeMillis() + (seconds * 1000);
    }

    public boolean isOverridingCooldown() {
        if (role.greaterEqual(Role.MODERATOR)) return overrideCooldown;
        return (overrideCooldown = false);
    }

    public void validateCaptcha() {
        flaggedForCaptcha = false;
        justShowedCaptcha = true;
    }

    public boolean isFlaggedForCaptcha() {
        return flaggedForCaptcha;
    }

    public void flagForCaptcha() {
        flaggedForCaptcha = true;
    }

    public String getName() {
        return name;
    }

    public List<Badge> getChatBadges() {
        List<Badge> toReturn = new ArrayList<>();

        if (getRole() != null && getRole().greaterEqual(Role.TRIALMOD)) {
            if (getRole().equals(Role.DEVELOPER)) {
                toReturn.add(new Badge("Developer", "Developer", "icon", "fas fa-wrench"));
            } else if (getRole().greaterEqual(Role.TRIALMOD)) {
                toReturn.add(new Badge(getRole().name(), getRole().name(), "icon", "fas fa-shield-alt"));
            }
        }

        int _count = getPixelsAllTime();
        if (_count >= 600000) {
            toReturn.add(new Badge("600k+", "600k+ Pixels Placed", "text", null));
        } else if (_count >= 500000) {
            toReturn.add(new Badge("500k+", "500k+ Pixels Placed", "text", null));
        } else if (_count >= 400000) {
            toReturn.add(new Badge("400k+", "400k+ Pixels Placed", "text", null));
        } else if (_count >= 300000) {
            toReturn.add(new Badge("300k+", "300k+ Pixels Placed", "text", null));
        } else if (_count >= 200000) {
            toReturn.add(new Badge("200k+", "200k+ Pixels Placed", "text", null));
        } else if (_count >= 100000) {
            toReturn.add(new Badge("100k+", "100k+ Pixels Placed", "text", null));
        } else if (_count >= 50000) {
            toReturn.add(new Badge("50k+", "50k+ Pixels Placed", "text", null));
        }

        return toReturn;
    }

    public boolean isShadowBanned() {
        return this.role == Role.SHADOWBANNED;
    }

    public boolean isBanned() {
        return banExpiryTime > System.currentTimeMillis() || role == Role.BANNED; // shadowbans are hidden....
    }

    public long getBanExpiryTime() {
        return banExpiryTime;
    }

    private void setBanExpiryTime(long timeFromNowSeconds, boolean skipSendUserData) {
        this.banExpiryTime = (timeFromNowSeconds*1000) + System.currentTimeMillis();
        App.getDatabase().updateBan(this, timeFromNowSeconds);
        if (!skipSendUserData && role != Role.SHADOWBANNED) {
            sendUserData();
        }
    }
    private void setBanExpiryTime(long timeFromNowSeconds) {
        setBanExpiryTime(timeFromNowSeconds, false);
    }

    public boolean isChatbanned() {
        return this.isPermaChatbanned || this.chatbanExpiryTime > System.currentTimeMillis();
    }

    public void chatban(Chatban chatban, boolean doLog) {
        System.out.printf("Initiating %s%n", chatban);
        switch (chatban.type) {
            case TEMP: {
                this.isPermaChatbanned = false;
                this.chatbanExpiryTime = chatban.expiryTime;
                App.getServer().getPacketHandler().sendChatban(this, new ServerChatBan(false, chatban.expiryTime));
                break;
            }
            case PERMA: {
                this.isPermaChatbanned = true;
                App.getServer().getPacketHandler().sendChatban(this, new ServerChatBan(true, null));
                break;
            }
            case UNBAN: {
                this.isPermaChatbanned = false;
                this.chatbanExpiryTime = chatban.expiryTime;
                App.getServer().getPacketHandler().sendChatban(this, new ServerChatBan(false, 0L));
                break;
            }
        }

        App.getDatabase().updateUserChatbanPerma(getId(), isPermaChatbanned);
        App.getDatabase().updateUserChatbanExpiry(getId(), chatbanExpiryTime);

        if (chatban.purge && chatban.purgeAmount > 0) {
            App.getDatabase().handlePurge(chatban.target, chatban.initiator, chatban.purgeAmount, "$automated chatban purge$", true);
        }

        if (doLog) {
            if (chatban.initiator == null) {
                App.getDatabase().adminLogServer(chatban.toString());
            } else {
                App.getDatabase().adminLog(chatban.toString(), chatban.initiator.getId());
            }
        }
    }

    public void chatban(Chatban chatban) {
        chatban(chatban, true);
    }

    public Set<WebSocketChannel> getConnections() {
        return connections;
    }

    public void shadowban(String reason, int rollbackTime, User banner) {
        setBanReason(reason);
        setRole(Role.SHADOWBANNED, true);
        App.rollbackAfterBan(this, rollbackTime);
        App.getDatabase().insertBanlog(System.currentTimeMillis(), banner == null ? 0 : banner.getId(), this.getId(), 0L, "shadowban", reason);
    }

    public void shadowban(String reason, User banner) {
        shadowban(reason, 24*3600, banner);
    }

    public void shadowban(User banner) {
        shadowban("", banner);
    }

    public void ban(long timeFromNowSeconds, String reason, int rollbackTime, User banner) {
        setBanExpiryTime(timeFromNowSeconds, true);
        setBanReason(reason);
        sendUserData();
        App.rollbackAfterBan(this, rollbackTime);
        Long now = System.currentTimeMillis();
        App.getDatabase().insertBanlog(now, banner == null ? 0 : banner.getId(), this.getId(), now + (timeFromNowSeconds * 1000), "ban", reason);
    }

    public void ban(long timeFromNowSeconds, String reason, User banner) {
        ban(timeFromNowSeconds, reason, 0, banner);
    }

    public void ban(long timeFromNowSeconds, User banner) {
        ban(timeFromNowSeconds, "", banner);
    }

    public void permaban(String reason, int rollbackTime, User banner) {
        setBanReason(reason);
        setRole(Role.BANNED, true);
        sendUserData();
        App.rollbackAfterBan(this, rollbackTime);
        Long now = System.currentTimeMillis();
        App.getDatabase().insertBanlog(now, banner == null ? 0 : banner.getId(), this.getId(), 0L, "permaban", reason);
    }

    public void permaban(String reason, User banner) {
        permaban(reason, 24*3600, banner);
    }

    public void permaban(User banner) {
        permaban("", banner);
    }

    public void unban(User whoUnbanned, String unbanReason) {
        setBanExpiryTime(0, true);
        if (role.lessThan(Role.USER)) {
            setRole(Role.USER, true);
        }
        sendUserData();
        App.undoRollback(this);
        Long now = System.currentTimeMillis();
        App.getDatabase().insertBanlog(now, whoUnbanned == null ? 0 : whoUnbanned.getId(), this.getId(), 0L, "unban", unbanReason);
    }

    public void setUseragent(String s) {
        useragent = s;
        App.getDatabase().updateUseragent(this, s);
    }

    public String getUseragent() {
        return useragent;
    }

    public int getStacked() {
        return stacked;
    }

    public void setStacked(int stacked) {
        this.stacked = stacked;
        App.getDatabase().updateUserStacked(this, stacked);
    }
    
    public long getInitialAuthTime() {
        return initialAuthTime;
    }

    public void setInitialAuthTime(long initialAuthTime) {
        this.initialAuthTime = initialAuthTime;
    }

    public boolean lastPlaceWasStack() {
        return lastPlaceWasStack;
    }

    public void setLastPlaceWasStack(boolean lastPlaceWasStack) {
        this.lastPlaceWasStack = lastPlaceWasStack;
    }

    public void tickStack() {
        tickStack(true);
    }

    private int addToN(int n) {
        int s = 0;
        for (int i = 1; i <= n; i++) {
            s += i;
        }
        return s;
    }

    public void tickStack(boolean sendRes) {
        int multiplier = App.getStackMultiplier();
        int maxStacked = App.getStackMaxStacked();
        
        int curCD = App.getServer().getPacketHandler().getCooldown();
        
        long lastPixelTime = getLastPixelTime() == 0 ? (this.cooldownExpiry == 0 ? getInitialAuthTime() : (this.cooldownExpiry - (curCD*1000))) : getLastPixelTime();
        if (lastPixelTime == 0) {
            return;
        }
        long delta = (System.currentTimeMillis()-lastPixelTime) / 1000;
        //App.getLogger().debug("=======");
        while(true) {
            int target = (curCD * multiplier) * (2 + getStacked() + addToN(getStacked()));
            //App.getLogger().debug(delta);
            //App.getLogger().debug(" : ");
            //App.getLogger().debug(target);
            if (delta >= target && getStacked() < maxStacked) {
                setStacked(getStacked() + 1);
                if (sendRes) {
                    App.getServer().getPacketHandler().sendAvailablePixels(this, "stackGain");
                }
                continue;
            }
            return;
        }
    }

    public int getPixels() {
        return App.getDatabase().getUserPixels(this.id);
    }

    public int getPixelsAllTime() {
        return App.getDatabase().getUserPixelsAllTime(this.id);
    }

    public int getAvailablePixels() {
        boolean canPlace = canPlace();
        if (!canPlace) return 0;

        return (canPlace ? 1 : 0) + this.stacked;
    }

    /**
     * Attempts to get placing lock. Weak implementation of a mutex lock.
     * When placingLocked, we're in the process of placing a pixel and database tables pertaining to placements shouldn't be updated until lock is released.
     *
     * @return True if a lock was acquired, false otherwise.
     */
    public boolean tryGetPlacingLock() {
        if (placingLock == true) return false;
        return placingLock = true;
    }

    public boolean isPlacingLocked() {
        return placingLock;
    }

    /**
     * Releases the placingLock.
     */
    public void releasePlacingLock() {
        placingLock = false;
    }

    public boolean isPermaChatbanned() {
        return isPermaChatbanned;
    }

    public long getChatbanExpiryTime() {
        return chatbanExpiryTime;
    }
}
