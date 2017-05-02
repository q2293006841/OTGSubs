package com.pddstudio.otgsubs;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.color.ColorChooserDialog;
import com.pddstudio.otgsubs.events.ColorChooserDialogEvent;
import com.pddstudio.otgsubs.events.RefreshItemListEvent;
import com.pddstudio.otgsubs.fragments.ThemePatcherFragment;
import com.pddstudio.otgsubs.models.ColorChooserType;
import com.pddstudio.otgsubs.services.PackageService;
import com.pddstudio.otgsubs.services.PatchTemplateService;
import com.pddstudio.otgsubs.utils.DeviceUtils;
import com.pddstudio.otgsubs.utils.Preferences_;
import com.pddstudio.otgsubs.utils.ThemeUtils;
import com.pddstudio.substratum.packager.models.ApkInfo;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.Click;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.Extra;
import org.androidannotations.annotations.OptionsItem;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.Receiver;
import org.androidannotations.annotations.ViewById;
import org.androidannotations.annotations.sharedpreferences.Pref;
import org.greenrobot.eventbus.Subscribe;

import java.io.File;

@EActivity(R.layout.activity_patcher)
@OptionsMenu(R.menu.menu_patcher)
public class PatcherActivity extends AppCompatActivity implements ColorChooserDialog.ColorCallback {

	private static final String TAG = PatcherActivity.class.getSimpleName();

	public static void open(@NonNull Context context, @NonNull ApkInfo apkInfo) {
		PatcherActivity_.intent(context).apkInfo(apkInfo).start();
	}

	@ViewById(R.id.toolbar)
	protected Toolbar toolbar;

	@Extra
	protected ApkInfo apkInfo;

	@Bean
	protected DeviceUtils deviceUtils;

	@Bean
	protected EventBusBean eventBus;

	@Bean
	protected PackageInfoBean packageInfoBean;

	@Pref
	protected Preferences_ preferences;

	private ColorChooserType colorChooserType = ColorChooserType.IGNORE;
	private MaterialDialog loadingDialog;
	private File assetFileDir;

	@Override
	@Click(android.R.id.home)
	public void onBackPressed() {
		super.onBackPressed();
	}

	@Override
	public void onColorSelection(@NonNull ColorChooserDialog dialog, @ColorInt int selectedColor) {
		String hexColor = String.format("#%06X", (0xFFFFFF & selectedColor));
		sendColorChosenEvent(hexColor);
		dialog.dismiss();
	}

	@Override
	public void onColorChooserDismissed(@NonNull ColorChooserDialog dialog) {
		sendColorChosenEvent(null);
		dialog.dismiss();
	}

	@Subscribe
	public void onColorDialogPickerEvent(ColorChooserDialogEvent event) {
		if(event != null && event.isOpenRequest()) {
			colorChooserType = event.getColorChooserType();
			int titleRes = event.getColorChooserType().equals(ColorChooserType.PRIMARY_COLOR) ? R.string.dialog_color_chooser_primary_title : R.string.dialog_color_chooser_primary_dark_title;
			new ColorChooserDialog.Builder(this, titleRes).allowUserColorInput(true).allowUserColorInputAlpha(true).show();
		} else {
			Log.w("ColorEvent", "Unknown event received!");
		}
	}

	@Subscribe
	public void onPatchingCompleted(RefreshItemListEvent event) {
		onBuildApkMenuItemSelected();
	}

	@Override
	protected void onStart() {
		super.onStart();
		eventBus.register(this);
	}

	@Override
	protected void onStop() {
		eventBus.unregister(this);
		super.onStop();
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		ThemeUtils.applySelectedTheme(this);
		super.onCreate(savedInstanceState);
	}

	@Override
	protected void onPause() {
		packageInfoBean.unregister();
		super.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
		deviceUtils.setNavigationBarColor(this);
		packageInfoBean.register();
	}

	@Receiver(actions = PatchTemplateService.ACTION_PATCHING_DONE, local = true)
	protected void onPatchingResultReceived(Intent intent) {
		boolean patchingSuccess = intent.getBooleanExtra(PatchTemplateService.EXTRA_PATCHING_SUCCESS, false);
		assetFileDir = (File) intent.getSerializableExtra(PatchTemplateService.EXTRA_PATCHING_ASSETS);
		if (patchingSuccess) {
			Toast.makeText(this, "Patching succeeded!", Toast.LENGTH_LONG).show();
		} else {
			Toast.makeText(this, "Patching failed!", Toast.LENGTH_LONG).show();
		}
	}

	@Receiver(actions = PackageService.ACTION_PACKAGING_DONE, local = true, registerAt = Receiver.RegisterAt.OnResumeOnPause)
	protected void onPackagingResultReceived(Intent intent) {
		boolean packagingSuccess = intent.getBooleanExtra(PackageService.EXTRA_PACKAGING_DONE_STATUS, false);
		String apkPath = intent.getStringExtra(PackageService.EXTRA_PACKAGING_DONE_FILE);
		toggleLoadingDialog(false);
		if (packagingSuccess) {
			Toast.makeText(this, "APK Created: " + apkPath, Toast.LENGTH_LONG).show();
			if(preferences.openPackageInstaller().getOr(true)) {
				Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", new File(apkPath));
				Intent promptInstall = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive");
				promptInstall.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
				startActivity(promptInstall);
			}
		} else {
			Toast.makeText(this, "Failed to create APK!", Toast.LENGTH_LONG).show();
		}
	}

	@AfterViews
	protected void setupUi() {
		toolbar.inflateMenu(R.menu.menu_patcher);
		toolbar.setSubtitle(apkInfo.getPackageName());
		setSupportActionBar(toolbar);
		if(getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		getSupportFragmentManager().beginTransaction()
								   .replace(R.id.fragment_placeholder, ThemePatcherFragment.newInstance(apkInfo), ThemePatcherFragment.TAG)
								   .commitAllowingStateLoss();
	}

	@OptionsItem(R.id.menu_build_apk)
	protected void onBuildApkMenuItemSelected() {
		Toast.makeText(this, "Build Apk clicked!", Toast.LENGTH_SHORT).show();
		toggleLoadingDialog(true);
		/*if(assetFileDir != null) {
			Log.d(TAG, "Packaging with: " + assetFileDir.getAbsolutePath());
			PackageService.packageApplication(this, assetFileDir);
		} else {
			PackageService.createApkFromRequestedAssets(this);
		}*/
		PackageService.createApkFromRequestedAssets(this);
	}

	private void sendColorChosenEvent(@Nullable String color) {
		if(color != null && !color.isEmpty() && colorChooserType != null) {
			ColorChooserDialogEvent colorChooserDialogEvent = new ColorChooserDialogEvent(false, colorChooserType);
			colorChooserDialogEvent.setResult(color);
			eventBus.post(colorChooserDialogEvent);
		}
		colorChooserType = ColorChooserType.IGNORE;
	}

	private void toggleLoadingDialog(boolean showDialog) {
		if (showDialog) {
			loadingDialog = new MaterialDialog.Builder(this).title(R.string.dialog_packaging_title)
															.content(R.string.dialog_packaging_content)
															.progress(true, -1)
															.cancelable(false)
															.canceledOnTouchOutside(false)
															.autoDismiss(false)
															.show();
		} else {
			if (loadingDialog != null && loadingDialog.isShowing()) {
				loadingDialog.dismiss();
			}
			loadingDialog = null;
		}
	}

}