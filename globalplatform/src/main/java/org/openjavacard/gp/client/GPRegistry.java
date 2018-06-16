/*
 * openjavacard-tools: OpenJavaCard Development Tools
 * Copyright (C) 2015-2018 Ingo Albrecht, prom@berlin.ccc.de
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 */

package org.openjavacard.gp.client;

import org.openjavacard.gp.protocol.GP;
import org.openjavacard.gp.protocol.GPPrivilege;
import org.openjavacard.iso.AID;
import org.openjavacard.iso.AIDInfo;
import org.openjavacard.iso.ISO7816;
import org.openjavacard.iso.SWException;
import org.openjavacard.tlv.TLVPrimitive;
import org.openjavacard.util.APDUUtil;
import org.openjavacard.util.HexUtil;
import org.openjavacard.util.VerboseString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.smartcardio.CardException;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for GlobalPlatform registry functionality
 * <p/>
 * The registry is the on-card database metadata about:
 * <ul>
 * <li>Security domains (ISD and SSD)</li>
 * <li>Executable load files (ELF) - equivalent to a JavaCard package</li>
 * <li>Executable modules (ExM) - equivalent to a loaded JavaCard applet</li>
 * <li>Applications (App) - equivalent to an installed JavaCard applet</li>
 * </ul>
 */
public class GPRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(GPRegistry.class);

    private static final int TAG_GP_REGISTRY = 0xE300;

    private static final int TAG_GP_REGISTRY_AID = 0x4F00;
    private static final int TAG_GP_REGISTRY_STATE = 0x9F70;
    private static final int TAG_GP_REGISTRY_PRIVILEGES = 0xC500;
    private static final int TAG_GP_REGISTRY_MODULE = 0x8400;

    /** Card being operated on */
    private final GPCard mCard;

    /** True if data needs refreshing */
    private boolean mDirty;

    /** List of all registry entries */
    private ArrayList<Entry> mAllEntries = new ArrayList<>();

    /** Entry for the ISD */
    private ISDEntry mISD = null;

    /** List of all applet entries */
    private ArrayList<AppEntry> mAllApps = new ArrayList<>();
    /** List of all SSD entries */
    private ArrayList<AppEntry> mAllSSDs = new ArrayList<>();
    /** List of all ELF entries */
    private ArrayList<ELFEntry> mAllELFs = new ArrayList<>();

    /**
     * Main constructor
     * @param card to operate on
     */
    GPRegistry(GPCard card) {
        mCard = card;
        mDirty = true;
    }

    /** @return registry entry for the ISD */
    public ISDEntry getISD() {
        try {
            update();
            return mISD;
        } catch (CardException e) {
            return null;
        }
    }

    /** @return all registry entries */
    public List<Entry> getAllEntries() {
        try {
            update();
            return new ArrayList<>(mAllEntries);
        } catch (CardException e) {
            return null;
        }
    }

    /** @return list of applet entries */
    public List<AppEntry> getAllApps() {
        try {
            update();
            return new ArrayList<>(mAllApps);
        } catch (CardException e) {
            return null;
        }
    }

    /** @return list of SSD entries */
    public List<AppEntry> getAllSSDs() {
        try {
            update();
            return new ArrayList<>(mAllSSDs);
        } catch (CardException e) {
            return null;
        }
    }

    /** @return list of ELF entries */
    public List<ELFEntry> getAllELFs() {
        try {
            update();
            return new ArrayList<>(mAllELFs);
        } catch (CardException e) {
            return null;
        }
    }

    /**
     * Find an applet or package
     *
     * Used specifically for deletion.
     *
     * @param aid to search for
     */
    public Entry findAppletOrPackage(AID aid) {
        Entry res;
        res = findApplet(aid);
        if(res != null) {
            return res;
        }
        res = findPackage(aid);
        if(res != null) {
            return res;
        }
        return res;
    }

    /**
     * Find an applet entry
     * @param aid to search for
     * @return applet entry or null
     */
    public AppEntry findApplet(AID aid) {
        try {
            update();
            for(AppEntry app: mAllApps) {
                if(app.mAID.equals(aid)) {
                    return app;
                }
            }
            return null;
        } catch (CardException e) {
            return null;
        }
    }

    /**
     * Return true if applet is present
     * @param aid to search for
     * @return true if present
     */
    public boolean hasApplet(AID aid) {
        return findApplet(aid) != null;
    }

    /**
     * Find a package entry
     * @param aid to search for
     * @return package entry or null
     */
    public ELFEntry findPackage(AID aid) {
        try {
            update();
            for (ELFEntry elf : mAllELFs) {
                if (elf.mAID.equals(aid)) {
                    return elf;
                }
            }
            return null;
        } catch (CardException e) {
            return null;
        }
    }

    /**
     * Return true if package is present
     * @param aid to search for
     * @return true if present
     */
    public boolean hasPackage(AID aid) {
        return findPackage(aid) != null;
    }

    /**
     * Find a package entry containing the specified module
     * @param aid to search for
     * @return package entry or null
     */
    public ELFEntry findPackageForModule(AID aid) {
        try {
            update();
            for (ELFEntry elf : mAllELFs) {
                for (AID mod : elf.mModules) {
                    if (mod.equals(aid)) {
                        return elf;
                    }
                }
            }
            return null;
        } catch (CardException e) {
            return null;
        }
    }

    /**
     * Mark registry as dirty
     */
    public void dirty() {
        LOG.debug("dirty()");
        mDirty = true;
    }

    /**
     * Perform a full update of the registry
     */
    public void update() throws CardException {
        if(!mDirty) {
            return;
        }
        LOG.debug("update()");

        try {
            ISDEntry isdEntry = null;
            ArrayList<Entry> allEntries = new ArrayList<>();
            ArrayList<AppEntry> allApps = new ArrayList<>();
            ArrayList<AppEntry> allSSDs = new ArrayList<>();
            ArrayList<ELFEntry> allELFs = new ArrayList<>();

            LOG.debug("reading ISD");
            List<ISDEntry> isdEntries = readEntriesTLV(GP.GET_STATUS_P1_ISD_ONLY, ISDEntry.class);
            if(!isdEntries.isEmpty()) {
                allEntries.addAll(isdEntries);
                isdEntry = isdEntries.get(0);
            }

            LOG.debug("reading APPs and SSDs");
            List<AppEntry> appEntries = readEntriesTLV(GP.GET_STATUS_P1_APP_AND_SD_ONLY, AppEntry.class);
            if(!appEntries.isEmpty()) {
                allEntries.addAll(appEntries);
                for (AppEntry appEntry : appEntries) {
                    allApps.add(appEntry);
                }
            }

            LOG.debug("reading ELFs and EXMs");
            List<ELFEntry> elfEntries = readEntriesTLV(GP.GET_STATUS_P1_EXM_AND_ELF_ONLY, ELFEntry.class);
            if(!elfEntries.isEmpty()) {
                allEntries.addAll(elfEntries);
                allELFs.addAll(elfEntries);
            }

            mISD = isdEntry;
            mAllEntries = allEntries;
            mAllApps = allApps;
            mAllELFs = allELFs;
            mAllSSDs = allSSDs;

            mDirty = false;
        } catch (CardException | IOException e) {
            throw new CardException("Error updating registry", e);
        }
    }

    private <E extends Entry>
    List<E> readEntriesTLV(byte p1Subset, Class<E> clazz) throws IOException, CardException {
        byte format = GP.GET_STATUS_P2_FORMAT_TLV;
        byte[] data = readStatus(p1Subset, format);
        List<E> res = new ArrayList<>();
        List<TLVPrimitive> tlvs = TLVPrimitive.readPrimitives(data);
        for (TLVPrimitive tlv : tlvs) {
            try {
                E entry = clazz.newInstance();
                entry.read(tlv.getValueBytes());
                res.add(entry);
            } catch (InstantiationException | IllegalAccessException e) {
                throw new Error("Error instantiating registry entry", e);
            }
        }
        return res;
    }

    /**
     * Perform a GlobalPlatform READ STATUS operation
     * <p/>
     * Convenience form. XXX: document
     * <p/>
     * @param p1Subset
     * @param p2Format
     * @return data retrieved
     * @throws CardException on error
     */
    private byte[] readStatus(byte p1Subset, byte p2Format) throws CardException {
        byte[] criteria = {0x4F, 0x00}; // XXX !?
        return readStatus(p1Subset, p2Format, criteria);
    }

    /**
     * Perform a GlobalPlatform SET STATUS operation
     *
     * @param p1Subset
     * @param p2Format
     * @param criteria
     * @return data retrieved
     * @throws CardException on error
     */
    private byte[] readStatus(byte p1Subset, byte p2Format, byte[] criteria) throws CardException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean first = true;
        do {
            // determine first/next parameter
            byte getParam = GP.GET_STATUS_P2_GET_NEXT;
            if (first) {
                getParam = GP.GET_STATUS_P2_GET_FIRST_OR_ALL;
            }
            first = false;
            // build the command
            CommandAPDU command = APDUUtil.buildCommand(
                    GP.CLA_GP,
                    GP.INS_GET_STATUS,
                    p1Subset, (byte) (getParam | p2Format), criteria);
            // run the command
            ResponseAPDU response = mCard.transactSecure(command);
            // get SW and data
            int sw = response.getSW();
            byte[] data = response.getData();
            // append data, no matter the SW
            if (data != null && data.length > 0) {
                bos.write(data, 0, data.length);
            }
            // continue if SW says that we should
            //   XXX extract this constant
            if (sw == 0x6310) {
                continue;
            }
            // check for various cases of "empty"
            //   XXX rethink this loop
            if (sw == ISO7816.SW_NO_ERROR
                    || sw == ISO7816.SW_FILE_NOT_FOUND
                    || sw == ISO7816.SW_REFERENCED_DATA_NOT_FOUND) {
                break;
            } else {
                throw new SWException("Error in GET STATUS", sw);
            }
        } while (true);
        return bos.toByteArray();
    }

    /**
     * Types of registry entries
     */
    public enum Type {
        ISD, APP, SSD, ELF
    }

    /**
     * Registry entries
     */
    public static abstract class Entry implements VerboseString {
        final Type mType;
        AID mAID;
        byte mState;
        byte[] mPrivileges;
        List<AID> mModules;

        Entry(Type type) {
            mType = type;
            mModules = new ArrayList<>();
        }

        public Type getType() {
            return mType;
        }

        public AID getAID() {
            return mAID;
        }

        public byte getState() {
            return mState;
        }

        public List<AID> getModules() {
            return mModules;
        }

        void read(byte[] data) throws IOException {
            List<TLVPrimitive> tlvs = TLVPrimitive.readPrimitives(data);
            List<AID> modules = new ArrayList<>();
            for (TLVPrimitive tlv : tlvs) {
                int tag = tlv.getTag();
                switch (tag) {
                    case TAG_GP_REGISTRY_AID:
                        mAID = new AID(tlv.getValueBytes());
                        break;
                    case TAG_GP_REGISTRY_STATE:
                        mState = tlv.getValueBytes()[0];
                        break;
                    case TAG_GP_REGISTRY_PRIVILEGES:
                        mPrivileges = tlv.getValueBytes();
                        break;
                    case TAG_GP_REGISTRY_MODULE:
                        modules.add(new AID(tlv.getValueBytes()));
                        break;
                    default:
                        LOG.warn("Unknown TLV in registry entry: " + tlv.toString());
                }
            }
            mModules = modules;
        }

        public String toString() {
            return mType.toString() + " " + mAID.toString();
        }

    }

    public static class AppEntry extends Entry {
        AppEntry() {
            super(Type.APP);
        }

        AppEntry(Type type) {
            super(type);
        }

        public boolean isSSD() {
            return (mPrivileges[0] & GPPrivilege.SECURITY_DOMAIN.privilegeBits) != 0;
        }

        public String toVerboseString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mType.toString());
            sb.append(" ");
            sb.append(mAID.toString());
            AIDInfo aidInfo = AIDInfo.get(mAID);
            if(aidInfo != null) {
                sb.append("\n  Label: ");
                sb.append(aidInfo.label);
            }
            sb.append("\n  State: ");
            sb.append(GP.appletStateString(mState));
            sb.append("\n  Privileges:");
            sb.append(GPPrivilege.printPrivileges(mPrivileges, "\n    ", ""));
            return sb.toString();
        }
    }

    public static class ISDEntry extends AppEntry {
        ISDEntry() {
            super(Type.ISD);
        }

        public String toVerboseString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mType.toString());
            sb.append(" ");
            sb.append(mAID.toString());
            AIDInfo aidInfo = AIDInfo.get(mAID);
            if(aidInfo != null) {
                sb.append("\n  Label: ");
                sb.append(aidInfo.label);
            }
            sb.append("\n  State: ");
            sb.append(GP.cardStateString(mState));
            sb.append("\n  Privileges:");
            sb.append(GPPrivilege.printPrivileges(mPrivileges, "\n    ", ""));
            return sb.toString();
        }
    }

    public static class ELFEntry extends Entry {
        ELFEntry() {
            super(Type.ELF);
        }

        public String toVerboseString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mType.toString());
            sb.append(" ");
            sb.append(mAID.toString());
            AIDInfo aidInfo = AIDInfo.get(mAID);
            if(aidInfo != null) {
                sb.append("\n  Label: ");
                sb.append(aidInfo.label);
            }
            sb.append("\n  State: ");
            sb.append(GP.elfStateString(mState));
            if (mModules != null && !mModules.isEmpty()) {
                for (AID module : mModules) {
                    sb.append("\n  ExM ");
                    sb.append(module.toString());
                }
            }
            return sb.toString();
        }
    }

}
