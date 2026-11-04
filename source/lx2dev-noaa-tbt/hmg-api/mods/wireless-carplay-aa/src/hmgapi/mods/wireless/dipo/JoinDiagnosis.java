package hmgapi.mods.wireless.dipo;

/**
 * Why the phone did not appear on the hotspot, from what this app can see. The JOIN step has several different ways of failing and
 * each one points somewhere else; this puts the first one that applies into one word and a sentence.
 *
 * What cannot be told from here: a phone that tried and failed the WPA handshake looks exactly like a phone that never tried (neither
 * sends a data frame to the host): PHONE_SILENT says so. Only the hostapd/driver log can separate them.
 */
final class JoinDiagnosis {
    private JoinDiagnosis() {}

    static final class In {
        /** the iAP2 server sent the phone a Wi-Fi configuration (null: not known, the scan did not run) */
        Boolean told;
        /** the phone asked for it */
        boolean asked;
        /** what the phone was told equals the hotspot's own name / key (null: not compared) */
        Boolean toldSsidEqual, toldPskEqual;
        ApAudit.Facts ap;
        /** bytes received on the hotspot interface since BT_ATTACHED (-1 unknown) and resolved ARP entries on it */
        long rx = -1;
        int arp;
    }

    static final class Out {
        final String code, text;

        Out(String code, String text) {
            this.code = code;
            this.text = text;
        }

        @Override
        public String toString() {
            return code + ": " + text;
        }
    }

    static Out classify(In i) {
        if (i.arp > 0) return new Out("JOINED", "the phone has an entry on the hotspot interface");
        if (Boolean.FALSE.equals(i.told))
            return new Out("NOT_TOLD", "the iAP2 server did not send the phone a Wi-Fi configuration (the phone " + (i.asked ? "did" : "did not") + " ask for one)");
        if (Boolean.TRUE.equals(i.told) && (Boolean.FALSE.equals(i.toldSsidEqual) || Boolean.FALSE.equals(i.toldPskEqual))) {
            String what = (Boolean.FALSE.equals(i.toldSsidEqual) ? "the name" : "") + (Boolean.FALSE.equals(i.toldSsidEqual) && Boolean.FALSE.equals(i.toldPskEqual) ? " and " : "")
                    + (Boolean.FALSE.equals(i.toldPskEqual) ? "the key" : "");
            return new Out("TOLD_OTHER_CREDENTIALS", "the phone was told " + what + " of a network that is not the one the access point broadcasts");
        }
        ApAudit.Facts a = i.ap;
        if (a != null) {
            if (a.procRead && !a.hostapdRunning) return new Out("AP_DOWN", "no hostapd process is running although the system reports the AP enabled");
            if (a.confRead && (!a.confSsidMatches || !a.confPskMatches))
                return new Out("AP_CONF_DIFFERS", "hostapd was started with " + (!a.confSsidMatches ? "another SSID" : "") + (!a.confSsidMatches && !a.confPskMatches ? " and " : "")
                        + (!a.confPskMatches ? "another passphrase" : "") + " than the Wi-Fi service's configuration");
            if (a.ifaceKnown && (!a.ifaceUp || !a.ifaceHasIpv4))
                return new Out("NO_ADDRESS_ON_AP", "the hotspot interface is " + (a.ifaceUp ? "up" : "down") + " and has " + (a.ifaceHasIpv4 ? "an" : "no") + " IPv4 address: nothing could hand out addresses");
            if (a.subnetClash) return new Out("SUBNET_CLASH", "another interface (the ADB network switch's eth0) is on the hotspot's subnet: DHCP offers leave through the wrong interface");
            if (a.procRead && !a.dnsmasqRunning) return new Out("NO_DHCP_SERVER", "no dnsmasq process: a phone that joined would get no address");
        }
        if (i.rx > 0)
            return new Out("PHONE_SENT_NO_ADDRESS", "frames arrive from the phone (" + i.rx + " bytes) but it has no ARP entry: association worked, address assignment did not");
        String told = i.told == null ? " (what the phone was told is not known)" : "";
        return new Out("PHONE_SILENT", "the access point side looks right and the phone sent nothing: it is not associated, either because it did not try or because it failed the WPA handshake "
                + "(cannot be told apart from here: needs the hostapd/driver log)" + told);
    }
}
