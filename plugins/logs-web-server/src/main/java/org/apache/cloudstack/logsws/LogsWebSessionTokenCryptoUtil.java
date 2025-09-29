// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.logsws;

import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import com.cloud.serializer.GsonHelper;

public class LogsWebSessionTokenCryptoUtil {
    private static final String ALGORITHM = "AES";
    public static final String TRANSFORMATION = "AES";

    public static String encrypt(LogsWebSessionTokenPayload payload, String key) throws GeneralSecurityException {
        String json = GsonHelper.getGson().toJson(payload);
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(), ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(json.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static LogsWebSessionTokenPayload decrypt(String token, String key) throws GeneralSecurityException {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(), ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(token));
        String json = new String(decrypted);
        return GsonHelper.getGson().fromJson(json, LogsWebSessionTokenPayload.class);
    }
}
