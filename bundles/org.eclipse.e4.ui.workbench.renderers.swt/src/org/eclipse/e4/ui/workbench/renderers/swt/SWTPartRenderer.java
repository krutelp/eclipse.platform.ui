/*******************************************************************************
 * Copyright (c) 2008, 2018 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Ragnar Nevries <r.eclipse@nevri.es> - Bug 443514
 *     Lars Vogel <Lars.Vogel@vogella.com> - Bug 472654
 *******************************************************************************/
package org.eclipse.e4.ui.workbench.renderers.swt;

import java.util.List;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.services.log.Logger;
import org.eclipse.e4.ui.css.core.engine.CSSEngine;
import org.eclipse.e4.ui.css.swt.dom.WidgetElement;
import org.eclipse.e4.ui.internal.workbench.swt.AbstractPartRenderer;
import org.eclipse.e4.ui.internal.workbench.swt.CSSConstants;
import org.eclipse.e4.ui.internal.workbench.swt.Policy;
import org.eclipse.e4.ui.internal.workbench.swt.WorkbenchSWTActivator;
import org.eclipse.e4.ui.model.application.descriptor.basic.MPartDescriptor;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.MUILabel;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IStylingEngine;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.e4.ui.workbench.IResourceUtilities;
import org.eclipse.e4.ui.workbench.swt.util.ISWTResourceUtilities;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;

public abstract class SWTPartRenderer extends AbstractPartRenderer {

	private static final String ADORN_ICON_IMAGE_KEY = "previouslyAdorned"; //$NON-NLS-1$

	private String pinURI = "platform:/plugin/org.eclipse.e4.ui.workbench.renderers.swt/icons/full/ovr16/pinned_ovr.svg"; //$NON-NLS-1$
	private Image pinImage;

	private ISWTResourceUtilities resUtils;

	@Override
	public void processContents(MElementContainer<MUIElement> container) {
		// EMF gives us null lists if empty
		if (container == null)
			return;

		// Process any contents of the newly created ME
		List<MUIElement> parts = container.getChildren();
		if (parts != null) {
			// loading a legacy app will add children to the window while it is
			// being rendered.
			// this is *not* the correct place for this
			// hope that the ADD event will pick up the new part.
			IPresentationEngine renderer = context.get(IPresentationEngine.class);
			for (int i = 0; i < parts.size(); i++) {
				MUIElement childME = parts.get(i);
				renderer.createGui(childME);
			}
		}
	}

	public void styleElement(MUIElement element, boolean active) {
		if (!active)
			element.getTags().remove(CSSConstants.CSS_ACTIVE_CLASS);
		else
			element.getTags().add(CSSConstants.CSS_ACTIVE_CLASS);

		if (element.getWidget() != null)
			setCSSInfo(element, element.getWidget());
	}

	public void setCSSInfo(MUIElement me, Object widget) {
		// No SWT widget, nothing to style...
		if (widget == null) {
			return;
		}

		//
		if (widget instanceof Widget) {
			Widget swtWidget = (Widget) widget;
			if (swtWidget.isDisposed()) {
				return;
			}
		}

		// Set up the CSS Styling parameters; id & class
		IEclipseContext ctxt = getContext(me);
		if (ctxt == null) {
			return;
		}

		final IStylingEngine engine = ctxt.get(IStylingEngine.class);
		if (engine == null)
			return;

		// Put all the tags into the class string
		EObject eObj = (EObject) me;
		StringBuilder builder = new StringBuilder('M' + eObj.eClass().getName());
		for (String tag : me.getTags()) {
			builder.append(' ').append(tag);
		}

		// this will trigger style()
		String id = me.getElementId();
		if (id != null) {
			id = id.replace('.', '-');
		}
		engine.setClassnameAndId(widget, builder.toString(), id);
	}

	protected void reapplyStyles(Widget widget) {
		CSSEngine engine = WidgetElement.getEngine(widget);
		if (engine != null) {
			engine.applyStyles(widget, false);
		}
	}

	@Override
	public void bindWidget(MUIElement me, Object widget) {
		if (widget instanceof Widget) {
			((Widget) widget).setData(OWNING_ME, me);

			// Set up the CSS Styling parameters; id & class
			setCSSInfo(me, widget);

			// Ensure that disposed widgets are unbound form the model
			Widget swtWidget = (Widget) widget;
			swtWidget.addDisposeListener(e -> {
				Widget w = e.widget;
				if (w.isDisposed()) {
					return;
				}
				MUIElement element = (MUIElement) w.getData(OWNING_ME);
				if (element != null)
					unbindWidget(element);
			});
		}

		// Create a bi-directional link between the widget and the model
		me.setWidget(widget);
	}

	public static Object unbindWidget(MUIElement me) {
		Widget widget = (Widget) me.getWidget();
		if (widget != null) {
			me.setWidget(null);
			if (!widget.isDisposed())
				widget.setData(OWNING_ME, null);
		}

		// Clear the factory reference
		me.setRenderer(null);

		return widget;
	}

	@Override
	protected Widget getParentWidget(MUIElement element) {
		return (Widget) element.getParent().getWidget();
	}

	@Override
	public void disposeWidget(MUIElement element) {
		disposeAdornedImage(element);
		if (element.getWidget() instanceof Widget) {
			Widget curWidget = (Widget) element.getWidget();

			if (curWidget != null && !curWidget.isDisposed()) {
				unbindWidget(element);
				try {
					curWidget.dispose();
				} catch (Exception e) {
					Logger logService = context.get(Logger.class);
					if (logService != null) {
						String msg = "Error disposing widget for : " + element.getClass().getName(); //$NON-NLS-1$
						if (element instanceof MUILabel) {
							msg += ' ' + ((MUILabel) element)
									.getLocalizedLabel();
						}
						logService.error(e, msg);
					}
				}
			}
		}
		element.setWidget(null);
	}

	@Override
	public void hookControllerLogic(final MUIElement me) {
		Object widget = me.getWidget();

		// add an accessibility listener (not sure if this is in the wrong place
		// (factory?)
		if (widget instanceof Control && me instanceof MUILabel) {
			((Control) widget).getAccessible().addAccessibleListener(
					AccessibleListener.getNameAdapter(e -> e.result = ((MUILabel) me).getLocalizedLabel()));
		}
	}

	public String getToolTip(MUILabel element) {
		String overrideTip = (String) ((MUIElement) element).getTransientData()
				.get(IPresentationEngine.OVERRIDE_TITLE_TOOL_TIP_KEY);
		return overrideTip == null ? element.getLocalizedTooltip()
				: overrideTip;
	}

	protected Image getImageFromURI(String iconURI) {
		if (iconURI == null || iconURI.length() == 0)
			return null;

		ImageRegistry registry = JFaceResources.getImageRegistry();
		Image image = registry.get(iconURI);
		if (image == null) {
			ImageDescriptor descriptor = resUtils.imageDescriptorFromURI(URI.createURI(iconURI));
			registry.put(iconURI, descriptor);
			image = registry.get(iconURI);
		}
		return image;
	}

	@Override
	public Image getImage(MUILabel element) {
		Image image = (Image) ((MUIElement) element).getTransientData().get(
				IPresentationEngine.OVERRIDE_ICON_IMAGE_KEY);
		if (image == null || image.isDisposed()) {
			image = getImageFromURI(getIconURI(element));
		}

		if (image != null) {
			image = adornImage((MUIElement) element, image);
		}

		return image;
	}

	private String getIconURI(MUILabel element) {
		if (element instanceof MPart) {
			MPart part = (MPart) element;
			String iconURI = part.getIconURI();

			if (iconURI == null) {
				MPartDescriptor desc = modelService.getPartDescriptor(part.getElementId());
				iconURI = desc != null ? desc.getIconURI() : null;
			}
			return iconURI;
		}
		return element.getIconURI();
	}


	private Image adornImage(MUIElement element, Image image) {
		if (imageChanged()) {
			disposeAdornedImage(element);// Need to dispose old image.If image changed
		}
		if (element.getTags().contains(IPresentationEngine.ADORNMENT_PIN)) {
			Image previousImage = (Image) element.getTransientData().get(ADORN_ICON_IMAGE_KEY);
			boolean exist = previousImage != null && !previousImage.isDisposed(); // Cached image exist
			if (!exist) {
				Image adornedImage = resUtils.adornImage(image, pinImage);
				if (adornedImage != image) {
					element.getTransientData().put(ADORN_ICON_IMAGE_KEY, adornedImage);
				}
				return adornedImage;
			}
			return previousImage;
		}
		return image;
	}

	/**
	 * Calculates the index of the element in terms of the other <b>rendered</b>
	 * elements. This is useful when 'inserting' elements in the middle of
	 * existing, rendered parents.
	 *
	 * @param element
	 *            The element to get the index for
	 * @return The visible index or -1 if the element is not a child of the
	 *         parent
	 */
	protected int calcVisibleIndex(MUIElement element) {
		MElementContainer<MUIElement> parent = element.getParent();

		int curIndex = 0;
		for (MUIElement child : parent.getChildren()) {
			if (child == element) {
				return curIndex;
			}

			if (child.getWidget() != null)
				curIndex++;
		}
		return -1;
	}

	protected int calcIndex(MUIElement element) {
		MElementContainer<MUIElement> parent = element.getParent();
		return parent.getChildren().indexOf(element);
	}

	@Override
	public void childRendered(MElementContainer<MUIElement> parentElement, MUIElement element) {
	}

	@Override
	public void init(IEclipseContext context) {
		super.init(context);

		resUtils = (ISWTResourceUtilities) context.get(IResourceUtilities.class);
		pinImage = getImageFromURI(pinURI);
	}

	@Override
	protected boolean requiresFocus(MPart element) {
		MUIElement focussed = getModelElement(Display.getDefault()
				.getFocusControl());
		if (focussed == null) {
			return true;
		}
		// we ignore menus
		do {
			if (focussed == element || focussed == element.getToolbar()) {
				return false;
			}
			focussed = focussed.getParent();
		} while (focussed != null);
		return true;
	}

	static protected MUIElement getModelElement(Control ctrl) {
		if (ctrl == null)
			return null;

		MUIElement element = (MUIElement) ctrl
				.getData(AbstractPartRenderer.OWNING_ME);
		if (element != null) {
			return element;
			// FIXME: DndUtil.getModelElement() has the following check;
			// do we need this?
			// if (modelService.getTopLevelWindowFor(element) == topLevelWindow)
			// {
			// return element;
			// }
			// return null;
		}

		return getModelElement(ctrl.getParent());
	}

	@Override
	public void forceFocus(MUIElement element) {
		if (element.getWidget() instanceof Control) {
			// Have SWT set the focus
			Control ctrl = (Control) element.getWidget();
			if (!ctrl.isDisposed()) {
				if (Policy.DEBUG_FOCUS) {
					WorkbenchSWTActivator.trace(Policy.DEBUG_FOCUS_FLAG, "Force focus for: " + element, null); //$NON-NLS-1$
				}
				ctrl.forceFocus();
			} else {
				if (Policy.DEBUG_FOCUS) {
					WorkbenchSWTActivator.trace(Policy.DEBUG_FOCUS_FLAG,
							"Trying to force focus for disposed control: " + element, new IllegalStateException()); //$NON-NLS-1$
				}
			}
		} else if (Policy.DEBUG_FOCUS) {
			WorkbenchSWTActivator.trace(Policy.DEBUG_FOCUS_FLAG,
					"Trying to force focus for non-control element: " + element, new IllegalStateException()); //$NON-NLS-1$
		}
	}

	private void disposeAdornedImage(MUIElement element) {
		Image previouslyAdornedImage = (Image) element.getTransientData().get(ADORN_ICON_IMAGE_KEY);
		if (previouslyAdornedImage != null) {
			previouslyAdornedImage.dispose();
			previouslyAdornedImage = null;
		}
	}

	protected boolean imageChanged() {
		return false;
	}
}
