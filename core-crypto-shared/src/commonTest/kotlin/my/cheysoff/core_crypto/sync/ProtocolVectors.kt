package my.cheysoff.core_crypto.sync

/**
 * Frozen protocol vectors: what this project's crypto produces for one fixed set of
 * inputs.
 *
 * GENERATED. Do not edit by hand. `ProtocolVectorsAreFrozenTest` (in `jvmTest`)
 * regenerates this file into `core-crypto-shared/build/generated-protocol-vectors/`
 * every time it runs, and fails when what it computes stops matching what is here.
 *
 * ## What these are for
 *
 * `ProtocolVectorsTest`, in `commonTest`, checks the crypto against them on **every**
 * target. On the JVM that is a regression guard. On an Apple target it is the answer
 * to the only question that matters about the Apple crypto actuals: does an iPhone
 * derive the same keys, compute the same record IDs and open the same envelopes as the
 * phone and the laptop? If it does not, an iPhone cannot read a note the others wrote,
 * and the failure presents as data corruption rather than as a crypto mismatch, which
 * is why this file exists at all.
 *
 * ## What they are NOT
 *
 * They are not evidence that the crypto is *correct*. They were produced by the same
 * implementation they test. Correctness is pinned separately, by published vectors
 * from RFC 4231, RFC 5869, RFC 7914 and the McGrew & Viega GCM paper — see
 * `PlatformCryptoKnownAnswerTest`, `HkdfTest` and `AesGcmKnownAnswerTest`. These two
 * kinds of vector answer different questions and neither substitutes for the other.
 *
 * ## Changing this file
 *
 * Every value here is one that an installed device has stored or will recompute.
 * Regenerating them to make a red test green does not fix anything — it removes the
 * alarm and leaves the break. See `ProtocolVectorsAreFrozenTest`'s KDoc.
 */
internal object ProtocolVectors {
    const val ARK: String = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    const val DEVICE_ID: String = "Zm9ydHktdHdvLWJ5dGVzLWlk"

    const val K_CONTENT: String = "3c1052721e696b352ede99c68dfb24e7f9132fe858ad7511026169e0f0e5829e"

    const val K_ID: String = "a042e112b84da9d075a9bd9dde76122b7ab244b1ea012794a87a42c9cc5b2bb7"

    const val ACCOUNT_ID: String = "2e319433bf1ddd233c22e1c2cb16c30b"

    const val ACCOUNT_ID_BASE64URL: String = "LjGUM78d3SM8IuHCyxbDCw"

    const val HLC_NODE: String = "1debb84e8005f0f9"

    const val NOTE_UUID: String = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"

    const val BLINDED_NOTE_ID: String = "wTrTFWb2mGkgaqPnfwvvtg"

    const val FOLDER_UUID: String = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    const val BLINDED_FOLDER_ID: String = "6gENNYIT0Z8kyov8bm9aGw"

    const val PER_RECORD_KEY: String = "c6b9dfb59da37997874d5acada1a41ee0049ee6e8be9df52a624064e969f5c1f"

    const val RECORD_ASSOCIATED_DATA: String = "01001677547254465762326d476b676171506e667776767467"

    const val RECORD_PAYLOAD: String = "{\"t\":\"note\",\"u\":\"1b4e28ba\",\"c\":\"hello \\u00e9\"}"

    const val RECORD_ENVELOPE: String = """
        01cafebabefacedbaddecaf88857d283c79eb18ad1549eaffdc9974c143d9fb44d9960ffbac9875dd25c4d5e006b026e
        4ec1ebb4dc9edb0b98bc8a4cf541e63df81c9fef3c0a7f9aa2d2af30eac1d3dfeedfd996ae9988fdbc23e417dcdfd059
        c1c35151c4799c3f7344a69328459f2df5e66dd1673ebcdda3c29d9621e1d017f88895c7f09a82263ff6e38c305a4437
        4abf1208f4aec4e62c9f479e7a48e2f26e9e473c5a0496e19081fed000cb658aa9b0f68f3a0b0d027740fb76bdf069f0
        ccbfed2136fcd0dc7aeed20ed0010c2f8b6dca5662442649d9809e31b3044907efb713a486159ab83ea945e79536b4b3
        731647af12701b3cd474272cdb589904f1784fbe021e65e201da920f7d38aa3a6bfa54e270b877a793db62982edfb10b
        68b970283de8de38833a042e00af99c2d2c65571fbdc3a7ee7a8729cb907d1854c3453a52b0a345607212410b3b9c31e
        bbe6a3bb3162896f3518909c413ff50faf29f30b2a8d0ee6327834a0d949fa365baea9fe083e0fec3d7bbcb3acf20b94
        76d0b6d3b48f2ec4694c692839a668e2e4653ff2e85bd393044625f4b75dfcda2f2b0b91a442f14af254e6597391ef95
        32d0b0769e4f4a4820f3563ce16d2503a9fe24933e01e077b4ffb882d657dcdfe25648090fe5af184639f66f1278ef2e
        f763667f11da8a4a97de9545e2cb5db35b1b3db18d2cb5fac8dec26df37d9f9cb99803f58dbe311341152e95a6b3e777
        c4844c3bcbb368707d158030d4d7ad837aadfad38c6140d5d5399d4ee6a8bcdb1f6a7394c322723183cd68e589c9858d
        afface5d390d8c74b53daa3a22200a69072165d2b6294fe6476724504c0fc99afc73d180212674924e5a491920375b79
        1b593028a71583d65d372c4499bb7ddd935fca9026d1638516646db902f0495e9fdc6ef8166350a6cc3edd1f43deac46
        bc73a7fe3929d2a60bb436dc95d3e62c93f87957b96c9ab7cccd2dac61123afce304360539ad86dd6d2b612e74d7ebb2
        4eab59a00a4ee95b97f8466de934fb2b3cdea9151f5fb01fdc01dc61d4eb6bd3663c1d1903975b9ae49d733ca05e5702
        6f62cedf8ed7657a40eb785ef7abbd761e8f07ee3c597df161ef65cc78e78906c0e5bc32462574177905d71bd2e3a80c
        3573c837851c42dcd09b7fc4a5696de1ca48edcbc0da3b292eb79dee2ce8f81c5c09dc77918159c68f8ebb0e771c59aa
        e2ee2b054bba5d899f8e702e6e72045fbe8d53ca19d51dbaef70d8208b01a4994ed871c3d7f5d5efb44eacead6a857e2
        bd2c778573af4d554aa676e7fbbf4b45390462ae82d528a3bed3839102fc499de2c3a50876c4f72f6154af302cedb7d4
        3d4f7b71afbd42605cb0d9ce8d1be2032fff366385532b57a101ce772e535afa05f41d7a85feb1cdae383f78c59756d4
        4e5a25a2441ea2edb26b9205d062fb957f3717fb0db145fa98d03eed9170b68274fe5c213b509bd79a11add513f151e6
        544d1f80b966bda3724e1d09ef5170675121f1733ca71fbb790a0f211d87a358537a55e71c6c684e07d520b3e3d89bd2
        7b60c001e6f3281e5522e55e416c1c2bf0b01100f33041a7641b1b4d84692ea11c154cf9f91f37ae6797e03ff3ad444a
        3ff5182872db2a0edf312c36fc3ab51427e992776e5f533d8c8b7e34d85b66090cf527ed83463ba6380ab17364c745cb
        ff95eff2fde47127f74b461b0d2ce631f0d82a549e83b0ac90fbdfccaf55830b88db58b94409684eae7a8fa81dbaa175
        8bdefcaf1abb14269787121e62092c5f677de787be3d5752603d773139dffe40de614b27404ca4f7620b540ba182009f
        fe81e9c1c0fc6d492d9351c84c5c3af0faec8eb3b0064d43846eb1877fa8ef80d4bd4a23462c5752607d5fab687916ce
        0c4d091df1c2e51a063455d439b1951a66f87675bc0966f6ea3cfe8685a478cc496aea4fddcc8dc41e1ec09e0fba9a3f
        76c755b43935774081679f0e66f86548c6c1031864c7c71006f8e085617a37cc5ba1d0adfee30638a2f2961384ec02e5
        392cf6df6d37df5072feb2199570ad77e04bd4fb206277ce8779ef4516c7cdef5bc06ceec4b9e41361dc76077f861f58
        1dbdbb007f2211604aabd1cc9e822d49c4a05d689f6ba03224cb2eb810d18ef70ffd8061e717a91b967856c3d22d58bd
        d33c8f222b2fa8ea4bd2c63354834c97fe7a8929a1ebcbdd0ba60ece4bfd62ea023f9d9c24816d570644eb8f6c212a01
        f43619698f5efc1fc5c5bd511dd83e69d7154de24543f4b51d4859714d47091f5e98cb15d42151644d5829a4c455d0d1
        d74842983bdf3fb6c2f6bf07e9f11566d9ca2487967c720a2b610c57d8db553a8d38773ebbc37e8c27a7eae863dc7534
        7ea39b479e16c271e22251e1932fe42a18f4ad354289d281f852f32a6a075eee1409f15ff33c7164dae3ee336417a6e9
        1b4723c7f11665d24dc20ad78ddf7842329b079c4e748f1117ba08945095c3c043116b6f0a49ba8688885c4cf1ee03a1
        e49fdd0f88669055ee3ed880a0f9f6587d6d14c45c2eb64d1fc7dc7e6afb3574b7e76c8fe1fafbca90492abf76821473
        9a942fa47b1bc37fe678906b08f54558484aa797189ca43b4297080e7d65511726cf198cc7676b4c74cbb0320d95e882
        d068dd803ba985d761d569a861b4b17132731992a503ff99f6bf68fea3a6dcd069bd2059122dc714685650e8f402643b
        151ea66644f8b441018a4014c99157bad4e82dccfbaa4104319b361a2d79651f4815c9f8b698cd7b114c2e806c191591
        ae36e2a031732ccd36097c51094fa7f87725b845231f04755c3041e8b68ec00e6a354eba164a87a63cf5f91f8caf82a6
        362e7095220e8852b8b4a59105072fb45910126393558039afb6fb42a6f2c6bde4c35a867edc8205eb4329eecafd4c46
        be3f49e65b9a21b55dfde037c6f99e211de33ce84b96f7536ebe5b032488112a0c039eb9237d0496d38cf55130021762
        810558d0f702e1d1f7955e2930c61de6dfa42957a71c21991001e3d47fb405d7350d29632cdb770d091b74327f5532d1
        65660a0bd49ae700088a748da6923d0b1c9691ed12f67a02f06949be60dfe1aa6967786edc4835a3daf68a1962255bfc
        2c565c33ac306011f274e5393b8cb0556c7cff662f5260bb8b8d0e3efff677912e995ff1e2b0b3a8574b7d5d77c1120d
        88094a7f21bdd8edf413e7f9059ff16a7e8803dae5a0c19240366bb97d19cf81cf83755e1a7a7631a5ac84085c7ae13b
        57def547905115df89bf2ad03549adfaae637e9319a59083f857be6cf7c7fc5a4179cbe07b4486ba21afba1c1e1a8ecb
        1cefb6c86e88e8c2eda6564bc689dfb89cf03980ed965c2a1e02a2aa88d0478dbb4450cd3499533dfc5fa04ff2357f25
        5b436e7b143b36e18999fb0853332517b031e91c84bc7af3c884822dca99b45876ec236ad40af673b69a9eba812341b9
        1ea7dd97565f393b130ba7b156b81150b748b608bd6e8385f991948d9cbb69a8ae4ac1ec8f6a2fdcd865f1aea472a8ee
        11d7edbbfd5e8f80edc631516c456d7f8f2de981f101d5fda61e8a61789ea5944c4595a581b749590207da5af2be5501
        aca9d11117559a73e931256999ef00c3562ac48dc2c51549bdb95dc59ce66eded4bced582b1a70e960cd706def8c9448
        51ec17153eb20cbbcfd30f92ff2650cec4f7f9604989826fce6e742c8d1d143eb62079528590d5d2f9cc9c47fb799c34
        5af42be13d880819b4b1fc0cde7452cccfef98b350ff41a5d597a177a74ba85589a0b1d6728bf831ee25b9715a87b92f
        dc20671c50f0975bf4f874d5a073c7ad8e387cd1b4d348302adf1910f7e4003fbd617d17c893514299653a6fdc5f3645
        8b3dec02687e4cc7d4d27d3cbfa3aaa40d89365c16eec778dacfc28b08746c9cee80228262949e55974cee43415f86e0
        69935c40410c7f4379a92efa5fc0d30d3361cf4a4088ed8f71e8ea8f596c56d93477eeebd4083c294241ea5bdcbdb7e0
        1e4285e49f6700683b98d9bce45184ad3b892d11cc7bace8314a72068d1972896011225815c2930e1bc2ccabf082a084
        f618f39406075a5ac2f58b15ff82f6b55e6bbf1facd663bf1713f853ce419ce55eec62d2c680577cb777a1d06de932cc
        3245c43f05180940d4d459cb06abc9a802753df02d32ba630dce7039212d11ad23ecc8f2ee23c54c3693624ac0e6beb0
        e5ea8188b779f8f83d487362d9a95ae6a0a2271df5348560e4e601d56ffbe19019bc8657e3f60da4c1c2ba7f2d6a829c
        023ea9929cebdc940e35df1e76ad4063f6435fc851bf592855b427787f6375108b5de62e8c50304d03ee1a6f131024d5
        7ab76e46a42502b24a92d749525aae3dae89c245884e956e71bd420a91687c54e4d966f4db2c18f8cdcb14336c792bc7
        cb05b3f4609c613fd93ce53ecd25586c39fcccdbef1b4c58fe64297021e32ac7aad9bd3b3fa857f60efae421c55f5678
        21606f0467dad0a8a01ec42df88e9bece8552f1f64866360b5bb7446753d044383f00d6e67def51702189ea177f6fc00
        b29ae771b5bb2ee1bce15953dbad272dd34e25537bf8511c8dca06a013be125a0c6caca2068040fed5ef652a155cf511
        5fb7fc924a7d117709a531d95df7c078df704eb183cffb07a283d71926a69cadddca7785582577ece0e18e707eae65fe
        b5175427aff2277b582e9251ef505d3be201d62e1a7a94dfb6388819bff4973ae9ccea3783ffc5b5aec640a36e0e4484
        1a77af4704369426daa3b32cd4c92b4a19908992f39e7aa44577d3c3239b69c17e726741a5ba60d939228fecf1bbb477
        2094066764da8bc1194767230dbaa0027d6d66d318d43e39052a492b51d61e34f7fd8cf0f3cfb32e1843fa64efb1b304
        f336ab60af15c6cfcc44f8c6d3f16390bd1062bdfd9923483502144512899399c6507cdbe6e1d237b1be7626a9bfc282
        00bc22f134141ef2a74a5feec8e80e2cfe75d81cc4e6ef1bca6e71b547205b0924f4beb17d14ab18cf5bc283a5b99ffe
        a5900af4be074784e4ff4cf7e278243371d4cea5b92541bf3e082dd2aa1b5367b2e592a22a0c067fda9e8b829bd404d8
        c08bb39b19dd3e07b347b102748018cec4b6ae91e5c082af0eb1038a6268ed3fe92f9fdc3f6f1d4afc94c8fdc3dabe6f
        5dcbf81d2c021cdbf3f2ee92203dd13f26e43643b45bf891bec284618b28340bc6a82387a52c952561828fea19641c41
        5b2d8da08f65ad29d85336f0a68acda0f8104fc4e1e70a874fc350b57cdecef2ec61abf2a5ddb3b986468ced7e9ef7d7
        d0d23dfc605af8b8bf4b516b3c4745860ab968dc4b080b5dcbd34888c86068b9290347d0f40faa6f2c51b4e49d3bca79
        7f3b9f8ee33052e0ad6d337a18b16936e2a80c6979f7c9e201dc4d75362adba4e83ff92081ddb7a84384fc6e451334ce
        de20161fdc192a59d99e0dc5bd2e20c10af3a8e9a04beb0ed2841204883d49a0005f2a4fd0b6dff5c388b5dbd6446fe9
        b8eb77ecc0823fb5a8744f6840f481a58a2d2eec6808253ed4ab5ed48cb2f1de68f988ff98e44e374d4b9a712b1f4b3e
        50ef16532b09c2034b8326334f09c33496acf81850a4360010fd7f7ab8b663841e9937cdefe3223c3deaf329d7313949
        4f6ffd57fc67ae7e598da13c1304b095e0fe0be57f3cf0a97686aa632f56b3480d578b5d48f1090226e3b6c1bcfe5544
        a66eedb0ad99a1034bb10062e3094dd8e3143309ac69f45399ec282cbf24de5c83396d702f511860c91e2894bb2367d6
        8c8d73606d85518075ed6ae3fe9aededd2df70d9c285fe89524027373bda216361951835a48694b4760ed02481
    """

    const val DB_PASSPHRASE: String = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"

    const val ARK_WRAP_IV: String = "101112131415161718191a1b"

    const val ARK_WRAP_CIPHERTEXT: String = "a3f0bb9a7a90cdeee5ce71245572eca99d5498b837531188309b2037321aea349d4c47b1a28a772bffabbe0c9139b8dd"

    const val DEVICE_PUBLIC_KEY_B64: String = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"

    const val DEVICE_LABEL: String = "Vova's Pixel 7"

    const val DEVICE_LABEL_ASSOCIATED_DATA: String = "0100244d466b77457759484b6f5a497a6a3043415159494b6f5a497a6a30444151634451674145"

    const val SEALED_DEVICE_LABEL: String = "01202122232425262728292a2b6d4d1059fb0b28f20702350d80a5a2d82ba7177e21aea87caba0d9b8dade4f1ca35453e520255a9bff2b99eaff703937c28138f08a7e01ddfbfeff209caff430d23516c85ee2ed472d976e5d942a0fea6307d14fde2e7743576a3fd3fe910f54fb102fbf02234c1dd439b1d3faa989530dcde2cbac74b1fe75aad725e4061b4c651497c33218ffe32d8fd0c2c7460dfe"

    const val PIN: String = "246813"

    const val PIN_WRAP_SALT: String = "303132333435363738393a3b3c3d3e3f"

    const val PIN_WRAP_IV: String = "404142434445464748494a4b"

    const val PIN_WRAP_CIPHERTEXT: String = "6af7c90699294b43280800fb481a896596bea3535cc41a2b6bb647c7c96da5eb"

    const val PASSPHRASE_PLAINTEXT: String = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
}
