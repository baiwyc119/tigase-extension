package org.kontalk.xmppserver;

import org.kontalk.xmppserver.pgp.KontalkKeyring;
import tigase.db.NonAuthUserRepository;
import tigase.db.TigaseDBException;
import tigase.server.Packet;
import tigase.server.Presence;
import tigase.util.Base64;
import tigase.xml.Element;
import tigase.xmpp.NotAuthorizedException;
import tigase.xmpp.XMPPPreprocessorIfc;
import tigase.xmpp.XMPPProcessor;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.impl.roster.RosterAbstract;
import tigase.xmpp.impl.roster.RosterAbstract.PresenceType;
import tigase.xmpp.impl.roster.RosterFactory;
import tigase.xmpp.impl.roster.RosterFlat;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Presence plugin to inject the subscriber public key when requesting
 * subscription the first time.
 * @author Daniele Ricci
 */
public class PresenceSubscribePublicKey extends XMPPProcessor implements
        XMPPPreprocessorIfc {

    private static final String ID = "presence/urn:xmpp:pubkey:2";

    private static final String ELEM_NAME = "pubkey";
    private static final String XMLNS = "urn:xmpp:pubkey:2";

    private static final Logger     log = Logger.getLogger(Presence.class.getName());

    private RosterAbstract roster_util = getRosterUtil();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean preProcess(Packet packet, XMPPResourceConnection session, NonAuthUserRepository repo, Queue<Packet> results, Map<String, Object> settings) {
        if (session != null && session.isAuthorized() && packet.getElemName().equals(Presence.ELEM_NAME)) {

            try {
                PresenceType presenceType = roster_util.getPresenceType(session, packet);
                if (presenceType == PresenceType.out_subscribe &&
                        !roster_util.isSubscribedFrom(session, packet.getStanzaTo())) {

                    // check if pubkey element was already added
                    if (!hasPublicKey(packet)) {
                        Packet res = addPublicKey(session, packet);
                        packet.processedBy(ID);
                        results.offer(res);
                        return true;
                    }
                }
            }
            catch (NotAuthorizedException e) {
                log.log(Level.WARNING, "not authorized!?", e);

            }
            catch (TigaseDBException e) {
                log.log(Level.SEVERE, "unable to access database", e);
            }

        }

        return false;
    }

    private boolean hasPublicKey(Packet packet) {
        return packet.getElement().getChild(ELEM_NAME, XMLNS) != null;
    }

    private Packet addPublicKey(XMPPResourceConnection session, Packet packet) throws NotAuthorizedException, TigaseDBException {
        String fingerprint = session.getData(KontalkCertificateCallbackHandler.DATA_NODE, "fingerprint", null);

        if (fingerprint != null) {
            try {
                byte[] keyData = KontalkKeyring.getInstance().exportKey(fingerprint);
                if (keyData != null && keyData.length > 0) {
                    Element pubkey = new Element(ELEM_NAME, new String[] { Packet.XMLNS_ATT }, new String[] { XMLNS });
                    pubkey.addChild(new Element("key", Base64.encode(keyData)));
                    pubkey.addChild(new Element("print", fingerprint));

                    Element presence = packet.getElement().clone();
                    presence.addChild(pubkey);
                    return Packet.packetInstance(presence, packet.getStanzaFrom(), packet.getStanzaTo());
                }
            }
            catch (IOException e) {
                log.log(Level.WARNING, "unable to load key for user " +
                    session.getBareJID(), e);
            }
        }

        return null;
    }

    /**
     * Returns shared instance of class implementing {@link RosterAbstract} -
     * either default one ({@link RosterFlat}) or the one configured with
     * <em>"roster-implementation"</em> property.
     *
     * @return shared instance of class implementing {@link RosterAbstract}
     */
    protected RosterAbstract getRosterUtil() {
        return RosterFactory.getRosterImplementation(true);
    }

}